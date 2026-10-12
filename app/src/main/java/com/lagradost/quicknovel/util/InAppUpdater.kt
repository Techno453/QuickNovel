package com.lagradost.quicknovel.util

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import androidx.preference.PreferenceManager
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinFeature
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.quicknovel.BuildConfig
import com.lagradost.quicknovel.CommonActivity.showToast
import com.lagradost.quicknovel.DownloadProgressState
import com.lagradost.quicknovel.DownloadState
import com.lagradost.quicknovel.MainActivity.Companion.app
import com.lagradost.quicknovel.NotificationHelper
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.mvvm.logError
import kotlinx.coroutines.runBlocking
import java.io.*
import java.net.URL
import java.net.URLConnection
import kotlin.concurrent.thread

const val UPDATE_TIME = 1000

class InAppUpdater {
    companion object {
        // === IN APP UPDATER ===
        @Volatile
        private var isDownloadingUpdate: Boolean = false

        private const val UPDATE_NOTIFICATION_ID = -1
        data class GithubAsset(
            @JsonProperty("name") val name: String,
            @JsonProperty("size") val size: Int, // Size bytes
            @JsonProperty("browser_download_url") val browser_download_url: String, // download link
            @JsonProperty("content_type") val content_type: String, // application/vnd.android.package-archive
        )

        data class GithubRelease(
            @JsonProperty("tag_name") val tag_name: String, // Version code
            @JsonProperty("body") val body: String, // Desc
            @JsonProperty("assets") val assets: List<GithubAsset>,
            @JsonProperty("target_commitish") val target_commitish: String, // branch
        )

        data class Update(
            @JsonProperty("shouldUpdate") val shouldUpdate: Boolean,
            @JsonProperty("updateURL") val updateURL: String?,
            @JsonProperty("updateVersion") val updateVersion: String?,
            @JsonProperty("changelog") val changelog: String?,
        )

        /** GitHub repo whose releases are offered as updates (the fork, not upstream). */
        private const val UPDATE_REPO = "Techno453/QuickNovel"

        /** Releases page, shown when an update cannot be installed over this build. */
        private const val RELEASES_PAGE = "https://github.com/$UPDATE_REPO/releases"

        /**
         * Rolling release that .github/workflows/pre-release.yml replaces on every push to master;
         * its single asset is `QuickNovel-<versionName>-pre.<CI run number>.apk`, a debug build.
         */
        private const val PRE_RELEASE_TAG = "pre-release"
        private val preReleaseBuildRegex = Regex("""pre\.(\d+)\.apk$""")

        /**
         * Which releases this build follows. Debug builds (applicationId `….debug`, installed next
         * to the release app) follow the rolling pre-release of debug APKs; release builds follow
         * the latest stable release. Each channel only ever offers its own kind of APK, so an
         * update always has this build's applicationId.
         */
        enum class Channel { STABLE, PRE_RELEASE }

        val channel: Channel get() = if (BuildConfig.DEBUG) Channel.PRE_RELEASE else Channel.STABLE

        /**
         * ABIs the release workflow publishes a split APK for (`QuickNovel-<version>-<abi>.apk`,
         * see `splits` in app/build.gradle and docs/ci.md), next to
         * `QuickNovel-<version>-universal.apk`.
         */
        internal val SPLIT_ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64")

        /**
         * The APK of a release to install on a device whose ABIs are [supportedAbis]
         * (`Build.SUPPORTED_ABIS`, most preferred first): the split APK of the first device ABI
         * that has one, else the universal APK, else the first APK whose name names no ABI (an
         * old single-APK release, or the pre-release). Null when the release only has other ABIs'
         * splits. Other assets (`…-mapping.txt`) are never chosen.
         */
        internal fun pickApkAsset(assets: List<GithubAsset>, supportedAbis: List<String>): GithubAsset? {
            val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
            fun abiOf(name: String): String? =
                SPLIT_ABIS.firstOrNull { name.endsWith("-$it.apk", ignoreCase = true) }
            for (abi in supportedAbis) {
                apks.firstOrNull { abiOf(it.name).equals(abi, ignoreCase = true) }?.let { return it }
            }
            apks.firstOrNull { it.name.endsWith("-universal.apk", ignoreCase = true) }?.let { return it }
            return apks.firstOrNull { abiOf(it.name) == null }
        }

        private val deviceAbis: List<String>
            get() = runCatching { Build.SUPPORTED_ABIS?.toList() }.getOrNull().orEmpty()

        /**
         * Upstream's comparison: the name of the asset [pickApkAsset] chooses must contain an
         * `x.y.z` version, which is compared as a string with the installed versionName.
         */
        internal fun stableUpdate(
            release: GithubRelease,
            installedVersionName: String?,
            supportedAbis: List<String> = deviceAbis,
        ): Update {
            val versionRegex = Regex("""(.*?((\d)\.(\d)\.(\d)).*\.apk)""")
            val foundAsset = pickApkAsset(release.assets, supportedAbis)
            val foundVersion = foundAsset?.name?.let { versionRegex.find(it) }
            val shouldUpdate =
                if (foundAsset?.browser_download_url != "" && foundVersion != null) installedVersionName?.compareTo(
                    foundVersion.groupValues[2]
                )!! < 0 else false
            return if (foundVersion != null) {
                Update(shouldUpdate, foundAsset.browser_download_url, foundVersion.groupValues[2], release.body)
            } else {
                Update(false, null, null, null)
            }
        }

        /** Pre-release channel: newer when the asset's CI run number is above this build's. */
        internal fun preReleaseUpdate(
            release: GithubRelease,
            installedBuild: Int,
            supportedAbis: List<String> = deviceAbis,
        ): Update {
            val asset = pickApkAsset(release.assets, supportedAbis) ?: return Update(false, null, null, null)
            val build = preReleaseBuildRegex.find(asset.name)?.groupValues?.get(1)?.toIntOrNull()
                ?: return Update(false, null, null, null)
            return Update(
                shouldUpdate = build > installedBuild && asset.browser_download_url.isNotBlank(),
                updateURL = asset.browser_download_url,
                updateVersion = asset.name.removePrefix("QuickNovel-").removeSuffix(".apk"),
                changelog = release.body,
            )
        }

        private val mapper = JsonMapper.builder().addModule(
            KotlinModule.Builder()
                .withReflectionCacheSize(512)
                .configure(KotlinFeature.NullToEmptyCollection, false)
                .configure(KotlinFeature.NullToEmptyMap, false)
                .configure(KotlinFeature.NullIsSameAsDefault, false)
                .configure(KotlinFeature.SingletonSupport, false)
                .configure(KotlinFeature.StrictNullChecks, false)
                .build()
        )
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).build()

        /** A GitHub `releases/…` API response (unit-tested: the release build maps it by reflection). */
        internal fun parseRelease(json: String): GithubRelease = mapper.readValue(json)

        private suspend fun Activity.getAppUpdate(): Update {
            try {
                val url = when (channel) {
                    Channel.STABLE -> "https://api.github.com/repos/$UPDATE_REPO/releases/latest"
                    Channel.PRE_RELEASE -> "https://api.github.com/repos/$UPDATE_REPO/releases/tags/$PRE_RELEASE_TAG"
                }
                val headers = mapOf("Accept" to "application/vnd.github.v3+json")
                val response = parseRelease(app.get(url, headers = headers).text)

                return when (channel) {
                    Channel.STABLE -> {
                        val currentVersion = packageName?.let {
                            packageManager.getPackageInfo(
                                it,
                                0
                            )
                        }
                        stableUpdate(response, currentVersion?.versionName)
                    }

                    Channel.PRE_RELEASE -> preReleaseUpdate(response, BuildConfig.CI_BUILD_NUMBER)
                }
            } catch (e: Exception) {
                println(e)
                return Update(false, null, null, null)
            }
        }

        private fun Activity.downloadUpdate(url: String): Boolean {
            if(isDownloadingUpdate) return false
            isDownloadingUpdate = true
            var fullResume = false // IF FULL RESUME
            try {
                // =================== DOWNLOAD POSTERS AND SETUP PATH ===================
                val path = filesDir.toString() +
                        "/Download/apk/update.apk"

                // =================== MAKE DIRS ===================
                val rFile = File(path)
                try {
                    rFile.parentFile?.mkdirs()
                } catch (t: Throwable){
                    logError(t)
                }

                val _url = URL(url.replace(" ", "%20"))

                val connection: URLConnection = _url.openConnection()

                var bytesRead = 0L

                // =================== STORAGE ===================
                try {
                    if (!rFile.exists()) {
                        rFile.createNewFile()
                    } else {
                        rFile.delete()
                        rFile.createNewFile()
                    }
                } catch (e: Exception) {
                    println(e)

                    showToast(R.string.permission_error, Toast.LENGTH_SHORT)
                    return false
                }

                // =================== CONNECTION ===================
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.connectTimeout = 10000
                var clen = 0
                try {
                    connection.connect()
                    clen = connection.contentLength
                } catch (t: Throwable) {
                    logError(t)
                }

                // =================== VALIDATE ===================
                if (clen < 5000000) { // min of 5 MB
                    clen = 0
                }
                if (clen <= 0) { // TO SMALL OR INVALID
                    //showNot(0, 0, 0, DownloadType.IsFailed, info)
                    isDownloadingUpdate = false
                    return false
                }

                // =================== SETUP VARIABLES ===================
                //val bytesTotal: Long = (clen + bytesRead.toInt()).toLong()
                val input: InputStream = BufferedInputStream(connection.inputStream)
                val output: OutputStream = FileOutputStream(rFile, false)
                var bytesPerSec = 0L
                val buffer = ByteArray(1024)
                var count: Int
                //var lastUpdate = System.currentTimeMillis()
                var lastUpdateTime = System.currentTimeMillis()

                while (true) {
                    try {
                        count = input.read(buffer)
                        if (count < 0) break

                        bytesRead += count
                        bytesPerSec += count
                        output.write(buffer, 0, count)

                        val currentTime = System.currentTimeMillis()
                        val timeElapsed = currentTime - lastUpdateTime
                        if (timeElapsed > 1000) {
                            val progress = bytesRead
                            val total = clen.toLong()

                            val bps = (bytesPerSec * 1000) / timeElapsed.coerceAtLeast(1)
                            val eta = if(bps > 0) (total - progress) / bps * 1000 else 0L
                            bytesPerSec = 0
                            lastUpdateTime = currentTime
                            println("bps: $bps | eta: ${eta/1000}s | progress: $progress/$total")

                            runBlocking {
                                NotificationHelper.createNotification(
                                    context = this@downloadUpdate,
                                    source = null,
                                    id = UPDATE_NOTIFICATION_ID,
                                    name = "QuickNovel Update",
                                    stateProgressState = DownloadProgressState(
                                        state = DownloadState.IsDownloading,
                                        downloaded = progress,
                                        progress = progress,
                                        total = total,
                                        lastUpdatedMs = lastUpdateTime,
                                        etaMs = eta
                                    ),
                                    progressInBytes = true,
                                    isActionable = false,
                                    isStreamNovel = false,
                                )
                            }
                            lastUpdateTime = currentTime
                        }

                    } catch (t: Throwable) {
                        logError(t)
                        fullResume = true
                        break
                    }
                }

                if (fullResume) { // IF FULL RESUME DELETE CURRENT AND DONT SHOW DONE
                    with(NotificationManagerCompat.from(this)) {
                        cancel(-1)
                    }
                }

                output.flush()
                output.close()
                input.close()
                runBlocking {
                    NotificationHelper.createNotification(
                        context = this@downloadUpdate,
                        source = url,
                        id = UPDATE_NOTIFICATION_ID,
                        name = "QuickNovel Update",
                        posterUrl = null,
                        stateProgressState = DownloadProgressState(
                            DownloadState.IsDone,
                            clen.toLong(),
                            clen.toLong(),
                            clen.toLong(),
                            lastUpdateTime,
                            null)
                    )
                }

                isDownloadingUpdate = false

                // Android refuses an APK with another package name or signing key as an update;
                // explain that instead of handing the installer an APK it will reject.
                installBlocker(rFile)?.let { reason ->
                    showCannotInstall(reason)
                    return true
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val contentUri = FileProvider.getUriForFile(
                        this,
                        BuildConfig.APPLICATION_ID + ".provider",
                        rFile
                    )
                    val install = Intent(Intent.ACTION_VIEW)
                    install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    install.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    install.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                    install.data = contentUri
                    startActivity(install)
                    return true
                } else {
                    val apkUri = Uri.fromFile(rFile)
                    val install = Intent(Intent.ACTION_VIEW)
                    install.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                    install.setDataAndType(
                        apkUri,
                        "application/vnd.android.package-archive"
                    )
                    startActivity(install)
                    return true
                }

            } catch (t: Throwable) {
                logError(t)
                isDownloadingUpdate = false
                return false
            }
        }

        @Suppress("DEPRECATION")
        private fun signaturesOf(info: PackageInfo?): Set<String>? {
            info ?: return null
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }
            } else {
                info.signatures
            }
            return signatures?.map { it.toCharsString() }?.toSet()?.takeIf { it.isNotEmpty() }
        }

        /**
         * Why [apk] cannot be installed as an update of this app (another applicationId or
         * another signing key), or null when it can or the platform does not tell us.
         */
        @Suppress("DEPRECATION")
        private fun Activity.installBlocker(apk: File): String? {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                PackageManager.GET_SIGNATURES
            }
            val archive = packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
                ?: return getString(R.string.update_not_an_apk)
            if (archive.packageName != packageName) {
                return getString(R.string.update_other_package, archive.packageName, packageName)
            }
            val update = signaturesOf(archive)
            val installed = signaturesOf(runCatching { packageManager.getPackageInfo(packageName, flags) }.getOrNull())
            if (update != null && installed != null && update.intersect(installed).isEmpty()) {
                return getString(R.string.update_other_key)
            }
            return null
        }

        private fun Activity.showCannotInstall(reason: String) {
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle(R.string.update_cannot_install_title)
                    .setMessage(reason)
                    .setPositiveButton(R.string.update_open_releases) { _, _ ->
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_PAGE))) }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }

        suspend fun Activity.runAutoUpdate(checkAutoUpdate: Boolean = true): Boolean {
            val settingsManager = PreferenceManager.getDefaultSharedPreferences(this)

            // A locally built debug app (CI build number 0) is signed with the developer's own key
            // and can never take a CI pre-release as an update: no prompt on start. A manual
            // "Check for update" still looks and explains the key mismatch if one is downloaded.
            if (checkAutoUpdate && channel == Channel.PRE_RELEASE && BuildConfig.CI_BUILD_NUMBER == 0) {
                return false
            }

            if (!checkAutoUpdate || settingsManager.getBoolean(
                    getString(R.string.auto_update_key),
                    true
                )
            ) {
                val update = getAppUpdate()
                if (update.shouldUpdate && update.updateURL != null) {
                    runOnUiThread {
                        val currentVersion = packageName?.let {
                            packageManager.getPackageInfo(
                                it,
                                0
                            )
                        }

                        val builder: AlertDialog.Builder = AlertDialog.Builder(this)
                        val installedLabel = when (channel) {
                            Channel.STABLE -> currentVersion?.versionName
                            Channel.PRE_RELEASE -> "${currentVersion?.versionName} (build ${BuildConfig.CI_BUILD_NUMBER})"
                        }
                        builder.setTitle(
                            getString(R.string.new_update_found_format).format(
                                installedLabel,
                                update.updateVersion
                            )
                        )
                        builder.setMessage(update.changelog)

                        val context = this
                        builder.apply {
                            setPositiveButton(R.string.update) { _, _ ->
                                showToast(context, R.string.download_started, Toast.LENGTH_LONG)
                                thread {
                                    val downloadStatus = context.downloadUpdate(update.updateURL)
                                    if (!downloadStatus) {
                                        showToast(
                                            R.string.download_failed,
                                            Toast.LENGTH_LONG
                                        )

                                    } /*else {
                                        activity.runOnUiThread {
                                            Toast.makeText(localContext,
                                                "Downloaded APK",
                                                Toast.LENGTH_LONG).show()
                                        }
                                    }*/
                                }
                            }

                            setNegativeButton(R.string.cancel) { _, _ -> }

                            if (checkAutoUpdate) {
                                setNeutralButton(R.string.dont_show_again) { _, _ ->
                                    settingsManager.edit().putBoolean("auto_update", false).apply()
                                }
                            }
                        }
                        builder.show()
                    }
                    return true
                }
                return false
            }
            return false
        }
    }
}