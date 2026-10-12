# Images and covers

Every cover in the app (search results, Browse rails, source pages, library, history, the result
page, the preview sheet, the download notification) and the reader's inline images go through one
Coil 3 image loader.

Code: `util/ImageModuleCoil.kt` (the loader, `ImageLoader.posterRequest`, the XML `loadImage`
helpers), `ui/common/PosterModel.kt` (what a cover is, header rules), `ui/common/PosterImage.kt`
(`PosterBox`, the placeholder rules), `util/PosterMemo.kt` (covers remembered per book),
`ui/common/ImmutableState.kt` (`ImmutableSearchResponse.posterModel` / `imageRequest`),
`ui/common/SearchViews.kt` (`CardPoster`).

## The loader

`BaseApplication` is Coil's `SingletonImageLoader.Factory`; `ImageLoader.buildImageLoader` builds
the single instance.

| Setting | Value | Why |
|---|---|---|
| Memory cache | 25% of the app's memory class | bitmaps are software (`allowHardware(false)`, Palette); a few screens of covers |
| Disk cache | 256 MB in `cacheDir/images` | fixed size; the pre-3.9.1 `cacheDir/qn_image_cache` is deleted once |
| Cache headers | ignored | Coil 3 has no `respectCacheHeaders`; its default strategy stores every response, so `Cache-Control: no-store` from a site does not stop a cover being kept. Only the LRU size evicts |
| HTTP client | `MainActivity.app.baseClient` + `DefaultUserAgentInterceptor` + `DynamicInterceptor` | the providers' client (shared connection pool, TLS settings, 30 s read timeout) |
| Crossfade | 200 ms | |
| Logging | `DebugLogger` in debug builds, errors only in release | tag `RealImageLoader` |

Decoded size: Compose `AsyncImage` and `ImageView.load` size the request to the view, so a rail
card decodes a card-sized bitmap even from a full-size `_ori` source image. The memory cache key
contains that size; the disk cache key is the url only, so the card and the result page share the
downloaded file.

## Requests and headers

`ImageLoader.posterRequest(context, PosterModel)` builds every cover request:

- `PosterModel(url, headers, file)` is value-equal (a data class), so `remember(model)` keeps the
  same request across recompositions and changes it only when the cover changes. The image
  caches are keyed on `data` (the file path or the url), never on the headers.
- Headers: the provider's `posterHeaders` (Referer, cookies, its own User-Agent), with the app's
  browser `USER_AGENT` added when the provider sent none (`PosterHeaders.withDefaults`; names
  compare case-insensitively, the provider wins). The interceptor adds the same user agent to any
  other Coil request without one (reader images), so no request goes out as `okhttp/x`.
- Cloudflare: `MainAPI.fixPosterHeaders` marks covers of `usesCloudFlareKiller` sources with
  `useCloudflareKiller: true`; `DynamicInterceptor` strips the marker and runs the request through
  `CloudflareKiller`.

## Which cover a card shows

`ImmutableSearchResponse.posterModel(context)`, in order:

1. Imported book: its `poster.jpg` (`filesDir/<source>/<author>/<name>/poster.jpg`).
2. Downloaded book: the `poster.jpg` the download saved at the same path, when it exists and is
   an image (not an HTML/JSON error page; `PosterModel.looksLikeImage`). Works offline and with an
   empty image cache.
3. The item's own `posterUrl` and `posterHeaders`.
4. The cover remembered for the book's url in `PosterMemo`.

`PosterMemo` (book page url -> cover url and headers, LRU of 4000, `filesDir/poster_memo.json`,
saved 2 s after a change) is filled by `APIRepository` from every loaded book page, search
result and listing page. Several listings give no cover at all (JJWXC and Piaotia rankings,
Quanben's plain lists, 69shuba's latest updates, Syosetu/Kakuyomu/Hameln, which have no covers),
while the book page does: before the memo, such a card stayed empty and the cover appeared only
on the result page. Now the card shows it as soon as the book has been opened once, and after a
restart. A card without a cover collects `PosterMemo.version`, so it updates when the memo
fills in.

## Placeholders

`PosterBox` (used by `CardPoster` and the result page header):

- loading: the Browse placeholder, a `surfaceVariant` block of the poster's shape;
- no cover known, or the load failed: the same block with the book's title (serif, bold) on it;
- an imported book without a cover file keeps its own placeholder (title on the book's colour).

A request with no data is never sent (no `NullRequestDataException` in the log).

## Debugging a cover that does not load

1. `adb logcat -s RealImageLoader` (debug build). Each request logs one line:
   `☁️ Successful (NETWORK)`, `💾 Successful (DISK)` (disk cache or a saved file),
   `🧠 Successful (MEMORY_CACHE)`, or `🚨 Failed` with the exception on the next line
   (`HttpException: HTTP 403` = headers or hotlink protection; `UnknownHostException` = offline).
2. No line at all for a card: the item has no cover and none is remembered (the card shows its
   title). Open the book once; its cover is then remembered. Check
   `run-as com.lagradost.quicknovel.debug cat files/poster_memo.json`.
3. 403 or an HTML body: compare the provider's `posterHeaders` with what the site needs (Referer
   of the site, not of the image host; some sites want a mobile User-Agent). Try
   `curl -I -A "<USER_AGENT>" -e "<mainUrl>/" <cover url>`.
4. Cloudflare sites: the source needs `usesCloudFlareKiller = true` so its covers carry the marker.
5. Library card shows an old or broken cover: delete
   `files/<source>/<author>/<name>/poster.jpg`; the next download writes it again.
6. Cache state: `run-as com.lagradost.quicknovel.debug du -sh cache/images`; clearing app cache in
   Android settings empties it (the memo and saved `poster.jpg` files stay).
