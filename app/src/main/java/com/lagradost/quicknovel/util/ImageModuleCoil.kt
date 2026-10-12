package com.lagradost.quicknovel.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.Log
import android.widget.ImageView
import androidx.annotation.DrawableRes
import coil3.EventListener
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.dispose
import coil3.load
import coil3.memory.MemoryCache
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.request.transformations
import coil3.transform.Transformation
import coil3.util.DebugLogger
import com.lagradost.quicknovel.BuildConfig
import com.lagradost.quicknovel.MainActivity
import com.lagradost.quicknovel.USER_AGENT
import com.lagradost.quicknovel.network.CloudflareKiller
import com.lagradost.quicknovel.ui.UiImage
import com.lagradost.quicknovel.ui.common.PosterHeaders
import com.lagradost.quicknovel.ui.common.PosterModel
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import okio.Path.Companion.toOkioPath
import java.io.File
import java.nio.ByteBuffer

// Taken from cs3

/**
 * Options for dynamic interceptor behavior per-request
 */

class DynamicInterceptor : Interceptor {
    private val cloudflareKiller by lazy { CloudflareKiller() }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        val useCloudflare = request.header(DefaultImagesHeaders.useCloudflareKillerHeader.first) == "true"
        val useIgnore500 = request.header(DefaultImagesHeaders.useIgnore500Header.first) == "true"

        val cleanRequest = request.newBuilder()
            .removeHeader(DefaultImagesHeaders.useCloudflareKillerHeader.first)
            .removeHeader(DefaultImagesHeaders.useIgnore500Header.first)
            .build()

        val response = if (useCloudflare) {
            cloudflareKiller.intercept(chain)
        } else {
            chain.proceed(cleanRequest)
        }
        return if (useIgnore500 && response.code == 500) {
            response.newBuilder()
                .code(200)
                .message("Forced OK from 500")
                .build()
        } else {
            response
        }
    }
}


/** Adds the app's browser user agent to image requests that carry none (OkHttp would send "okhttp/x"). */
class DefaultUserAgentInterceptor(private val userAgent: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header(PosterHeaders.USER_AGENT_HEADER) != null) return chain.proceed(request)
        return chain.proceed(request.newBuilder().header(PosterHeaders.USER_AGENT_HEADER, userAgent).build())
    }
}

/**
 * The one image loader of the app (Coil's singleton, built by BaseApplication). Covers, avatars
 * and reader images all go through it; docs/images.md has the numbers and the reasons.
 */
object ImageLoader {
    private const val TAG = "CoilImgLoader"

    /** Disk cache folder under cacheDir; the folder used before 3.9.1 is removed once. */
    const val DISK_CACHE_DIR = "images"
    private const val OLD_DISK_CACHE_DIR = "qn_image_cache"
    const val DISK_CACHE_BYTES = 256L * 1024 * 1024
    const val MEMORY_CACHE_PERCENT = 0.25

    internal fun buildImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .crossfade(200)
        .allowHardware(false) // SDK_INT >= 28, cant use hardware bitmaps for Palette Builder
        // Coil 3 ignores Cache-Control by default (there is no respectCacheHeaders any more), so
        // a cover stays on disk even when the site answers no-store; only the LRU size evicts it.
        .diskCachePolicy(CachePolicy.ENABLED)
        .networkCachePolicy(CachePolicy.ENABLED)
        .memoryCachePolicy(CachePolicy.ENABLED)
        .memoryCache {
            // A quarter of the app's memory class: software bitmaps (allowHardware false) for a
            // few screens of covers.
            MemoryCache.Builder().maxSizePercent(context, MEMORY_CACHE_PERCENT).build()
        }
        .diskCache {
            Thread {
                runCatching { context.cacheDir.resolve(OLD_DISK_CACHE_DIR).deleteRecursively() }
            }.start()
            DiskCache.Builder()
                .directory(context.cacheDir.resolve(DISK_CACHE_DIR).toOkioPath())
                .maxSizeBytes(DISK_CACHE_BYTES)
                .build()
        }
        /** Pass interceptors with care, unnecessary passing tokens to servers
        or image hosting services causes unauthorized exceptions **/
        .components {
            add(OkHttpNetworkFetcherFactory(callFactory = {
                // The providers' client (shared connection pool, TLS settings, 30 s read
                // timeout), plus the browser user agent and the per-request Cloudflare switch.
                MainActivity.app.baseClient
                    .newBuilder()
                    .addInterceptor(DefaultUserAgentInterceptor(USER_AGENT))
                    .addInterceptor(DynamicInterceptor())
                    .build()
            }))
        }
        .also {
            it.setupCoilLogger()
            Log.d(TAG, "buildImageLoader: Setting COIL Image Loader.")
        }
        .build()

    /**
     * The request for a cover: the saved file, or the url with its headers (plus the default user
     * agent) and the url as disk cache key. Every card, the result page and the preview sheet
     * build their cover requests here.
     */
    fun posterRequest(
        context: Context,
        model: PosterModel,
        transformations: List<Transformation> = emptyList(),
        builder: ImageRequest.Builder.() -> Unit = {},
    ): ImageRequest {
        val file = model.file
        return ImageRequest.Builder(context)
            .data(if (file != null) File(file) else model.url)
            .apply {
                if (file == null) {
                    httpHeaders(NetworkHeaders.Builder().also { headerBuilder ->
                        PosterHeaders.withDefaults(model.headers, USER_AGENT).forEach { (key, value) ->
                            headerBuilder[key] = value
                        }
                    }.build())
                    model.cacheKey?.let { diskCacheKey(it) }
                }
            }
            .crossfade(true)
            .transformations(transformations)
            .apply(builder)
            .build()
    }

    /** Use DebugLogger on debug builds which won't slow down release builds & use EventListener for
    Errors on release builds. **/
    internal fun ImageLoader.Builder.setupCoilLogger() {
        if (BuildConfig.DEBUG) {
            logger(DebugLogger())
            Log.d(TAG, "setupCoilLogger: Activated DEBUG_LOGGER FOR COIL")
        } else {
            eventListener(object : EventListener() {
                override fun onError(request: ImageRequest, result: ErrorResult) {
                    super.onError(request, result)
                    Log.e(TAG, "Error loading image: ${result.throwable}")
                }
            })
            Log.d(TAG, "setupCoilLogger: Activated EVENT_LISTENER FOR COIL")
        }
    }

    /** we use coil's built in loader with our global synchronized instance, this way we achieve
    latest and complete functionality as well as stability **/
    private fun ImageView.loadImageInternal(
        imageData: Any?,
        headers: Map<String, String>? = null,
        builder: ImageRequest.Builder.() -> Unit = {} // for placeholder, error & transformations
    ) {
        // clear image to avoid loading & flickering issue at fast scrolling (e.g, an image recycler)
        this.dispose()

        if(imageData == null) return // Just in case

        // setImageResource is better than coil3 on resources due to attr
        if(imageData is Int) {
            this.setImageResource(imageData)
            return
        }

        // Use Coil's built-in load method but with our custom module & a decent USER-AGENT always
        // which can be overridden by extensions.
        this.load(imageData, SingletonImageLoader.get(context)) {
            this.httpHeaders(NetworkHeaders.Builder().also { headerBuilder ->
                PosterHeaders.withDefaults(headers, USER_AGENT).forEach { (key, value) ->
                    headerBuilder[key] = value
                }
            }.build())
            builder() // if passed
        }
    }

    /** TYPE_SAFE_LOADERS **/
    fun ImageView.loadImage(
        imageData: UiImage?,
        builder: ImageRequest.Builder.() -> Unit = {}
    ) = when (imageData) {
        is UiImage.Image -> loadImageInternal(
            imageData = imageData.url,
            headers = imageData.headers,
            builder = builder
        )

        is UiImage.Bitmap -> loadImageInternal(imageData = imageData.bitmap, builder = builder)
        is UiImage.Drawable -> loadImageInternal(imageData = imageData.resId, builder = builder)
        null -> loadImageInternal(null, builder = builder)
        is UiImage.Uri -> loadImageInternal(imageData = imageData.uri, builder = builder)
    }

    fun ImageView.loadImage(
        imageData: String?,
        headers: Map<String, String>? = null,
        builder: ImageRequest.Builder.() -> Unit = {}
    ) = loadImageInternal(
        imageData = imageData,
        headers = headers,
        builder = builder
    )

    fun ImageView.loadImage(
        imageData: Uri?,
        headers: Map<String, String>? = null,
        builder: ImageRequest.Builder.() -> Unit = {}
    ) = loadImageInternal(imageData, headers, builder)

    fun ImageView.loadImage(
        imageData: HttpUrl?,
        headers: Map<String, String>? = null,
        builder: ImageRequest.Builder.() -> Unit = {}
    ) = loadImageInternal(imageData, headers, builder)

    fun ImageView.loadImage(
        imageData: File?,
        builder: ImageRequest.Builder.() -> Unit = {}
    ) = loadImageInternal(imageData = imageData, builder = builder)

    fun ImageView.loadImage(
        @DrawableRes imageData: Int?,
        builder: ImageRequest.Builder.() -> Unit = {}
    ) = loadImageInternal(imageData = imageData, builder = builder)

    fun ImageView.loadImage(
        imageData: Drawable?,
        builder: ImageRequest.Builder.() -> Unit = {}
    ) = loadImageInternal(imageData = imageData, builder = builder)

    fun ImageView.loadImage(
        imageData: Bitmap?,
        builder: ImageRequest.Builder.() -> Unit = {}
    ) = loadImageInternal(imageData = imageData, builder = builder)

    fun ImageView.loadImage(
        imageData: ByteArray?,
        builder: ImageRequest.Builder.() -> Unit = {}
    ) = loadImageInternal(imageData = imageData, builder = builder)

    fun ImageView.loadImage(
        imageData: ByteBuffer?,
        builder: ImageRequest.Builder.() -> Unit = {}
    ) = loadImageInternal(imageData = imageData, builder = builder)
}