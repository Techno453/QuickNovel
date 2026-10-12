# CJK sources

The app reads raw Chinese, Japanese and Korean web novels and translates them with the AI
translator. This page lists every CJK source, what works without an account, and the known
limits. Last checked from a desktop connection on 2026-10-11/12. Sites change, so treat the
"verified" notes as a snapshot.

## How a source declares its language

Every provider sets `MainAPI.lang` to an ISO 639-1 code (`"zh"`, `"ja"`, `"ko"`). Nothing else is
needed:

- `TranslationLanguages.normalize(api.lang)` picks the translator's source language, the
  translate button, title auto-translation and the reader's on-demand translation.
- `SearchQueryTranslator.translateQuery(query, api.lang)` translates the user's search query
  into the source language before calling `search()`.
- The provider-language setting (`getApiProviderLangSettings`, `zh`/`ja`/`ko` on by default) shows or
  hides the source.

A source that mixes languages (Pixiv) is narrowed to one language at the request level instead
(`work_lang=ja`), so the declared `lang` stays true.

## Overview

| Source | Lang | Search | Browse (main page) | Chapter text | Login | Cloudflare / geo | Class |
|---|---|---|---|---|---|---|---|
| 69shuba (Biquge) | zh | yes (Turnstile; WebView fallback) | yes | yes | no | Cloudflare; geo-blocked from some regions (HTTP 403 here) | `chinese/BiqugeProvider` |
| Quanben | zh | yes | yes | yes | no | none seen | `QuanbenProvider` |
| **Ixdzs (爱下电子书)** | zh | yes | yes | yes | no | none; own token check on chapter pages (handled) | `chinese/IxdzsProvider` |
| **Piaotia (飘天文学)** | zh | yes | yes | yes | no | Cloudflare passive script only, no challenge | `chinese/PiaotiaProvider` |
| Kakuyomu (カクヨム) | ja | yes | yes | yes | no | none | `japanese/KakuyomuProvider` |
| Syosetu (小説家になろう) | ja | yes | yes | yes | no | none | `SyosetuProvider` |
| **Alphapolis (アルファポリス)** | ja | yes | yes | yes (free episodes) | no | none; reCAPTCHA if it suspects a bot | `japanese/AlphapolisProvider` |
| **Pixiv Novels (pixiv小説)** | ja | yes | yes (by tag) | yes (all-ages works) | no | none | `japanese/PixivNovelProvider` |
| Novelpia (노벨피아) | ko | yes | yes | yes (free episodes) | no | none | `korean/NovelpiaProvider` |
| **Naver Webnovel (네이버 웹소설)** | ko | yes | yes | yes (best/challenge leagues) | no | none | `korean/NaverWebNovelProvider` |

Bold rows were added in this pass. Each one has fixture tests and a gated live test (below).

## Per-source notes

### 69shuba (Biquge), zh
GBK, Cloudflare. Plain GETs work, but search POSTs need a Turnstile token (the provider runs the
search in a WebView) and Cloudflare challenges after about 30 quick chapter fetches (the provider
spaces downloads and pauses in bursts). This machine gets HTTP 403 from the site (geo/IP), so it
could not be re-tested here.

### Quanben, zh
Upstream provider. Reachable (HTTP 200). Not re-tested in depth in this pass.

### Ixdzs (爱下电子书), zh — https://ixdzs8.com
- UTF-8, server-rendered. Search `/bsearch?q=`, category browse `/sort/{cat}/index-0-{status}-{order}-{page}.html`.
- The book page contains the whole table of contents in reading order (`#a-list`). The
  newest-first preview list above it is ignored. Chapter URLs `/read/{id}/p{n}.html` are stable.
- Chapter pages sometimes return a small "正在验证浏览器" page with a token. The provider requests
  `?challenge=<token>` without following redirects, keeps the `PHPSESSID` cookie for the session
  and fetches the chapter again.
- Mirrors: ixdzs.tw and ixdzs.hk serve the same ids in traditional characters (not used).
- Limitations: covers and chapter titles come from the site as-is. Site-name promo lines are
  removed from the text.

### Piaotia (飘天文学), zh — https://www.piaotia.com
- GBK (`charset=gb2312` meta, no charset header), decoded with `textWithEncoding()`. Classic
  jieqi CMS. Updated daily.
- Search `GET /modules/article/search.php?searchtype=articlename&searchkey=<GBK %-encoded>`. An
  exact title match redirects to the book page, and the provider turns that into a single
  result.
- Browse: `/booksort{cat}/0/{page}.html` (latest update by category) and global rankings
  `/booktop{allvisit|monthvisit|allvote|goodnum|postdate}/0/{page}.html`. The rankings ignore the
  category filter.
- Table of contents `/html/{shard}/{id}/index.html`. The chapter body is loose text in `<body>`
  between the top and bottom nav, so the parser strips the page chrome and splits on `<br>`.
- Cloudflare only injects its passive `/cdn-cgi/challenge-platform/scripts/jsd/` script. The
  provider deliberately does **not** set `usesCloudFlareKiller`, because that interceptor treats any
  page containing `/cdn-cgi/` in its first 10 KB as a challenge (see "Cloudflare" below).

### Kakuyomu (カクヨム), ja
Next.js Apollo state. All episodes are embedded on the work page. No login is needed.

### Syosetu, ja
Upstream provider. Chapter titles are placeholders ("Episode N") built from the last episode
number, and one-shot (短編) works, which have no episode list, come back with no chapters. The adult sister sites (Noc/Novel18, which need an
`over18=yes` cookie) are not covered.

### Alphapolis (アルファポリス), ja — https://www.alphapolis.co.jp
- Search `/search?query=&category=novel`. Browse `/novel/index?sort=&category_ids=` (24h,
  weekly, monthly points, recent, completed, favourites; 15 categories).
- The table of contents is the `app-cover-data` JSON on the work page. Episodes of works that
  became books turn into paid rentals (`isPublic:false`) and are left out of the chapter list.
- Episode text is not in the episode page. The page's own script POSTs
  `/novel/episode_body {episode, token}`. The provider does the same: GET the episode page, take
  the per-page token, the Laravel `_token` (sent as `X-CSRF-TOKEN`) and the session cookies, then
  POST. That is 2 requests per chapter, with `rateLimitTime` 1.5 s for downloads.
- Ruby: kana furigana is dropped, and other readings are kept as `base《reading》` (same as Kakuyomu).
- Limitations: if Alphapolis answers with a reCAPTCHA form (bot suspicion after heavy use), the
  chapter fails with a "solve the CAPTCHA in the in-app browser" error.

### Pixiv Novels (pixiv小説), ja — https://www.pixiv.net/novel
- JSON endpoints of the web app, readable logged out for all-ages works:
  `/ajax/search/novels/<q>`, `/ajax/novel/series/{id}`,
  `/ajax/novel/series_content/{id}?limit=30&last_order=` (paged), `/ajax/novel/{id}`.
- Search uses title/caption mode (`s_mode=s_tc`). Logged out, Pixiv rejects that with 例外エラー
  if `gs=1` (series grouping) is added, so the provider folds episodes into their series itself.
  If fewer than 5 hits come back, it adds a tag search. Browse is a tag search (`s_tag_full`,
  grouped) on genre tags, newest first. Popularity sort needs Premium and is not offered.
- Requests are limited to `mode=safe&work_lang=ja`. R-18 works and episodes are skipped, and
  login-only works raise "visible to logged-in users only".
- Markup: `[chapter:]`, `[[rb: > ]]`, `[[jumpuri: > ]]`, `[newpage]`, `[jump:]` are converted.
  Embedded illustrations (`[pixivimage:]`, `[uploadedimage:]`) are dropped, because i.pximg.net
  needs a pixiv `Referer` that the reader does not send. Covers carry `posterHeaders` with the
  Referer.
- Mostly fan fiction (二次創作). Tag "オリジナル" is the default browse tab.

### Novelpia (노벨피아), ko
Free and "free serial" episodes only. PLUS/19+ need an account and are skipped.

### Naver Webnovel (네이버 웹소설), ko — https://novel.naver.com
- Only the free amateur leagues are covered: 베스트리그 (`/best/`) and 챌린지리그
  (`/challenge/`). The paid catalogue (오늘의 웹소설, series.naver.com, cookie purchases) is out of
  scope, and search filters out its cards.
- Search runs `section=best` then `section=challenge`. Browse is `/{league}/genre?genre=&order=`.
  Carousels and side rankings are ignored.
- Episode lists are paginated 10 per page. The provider reads the total from `(N)` and fetches
  `order=Oldest&page=1..n` with a 300 ms pause, so the list is in reading order.
- Episode text is newline-formatted inside `div.detail_view_content`. The author's note below it
  is not included.
- Limitations: best-league works picked up for paid serialization lose their free episodes. They
  still show in search, with 0 chapters and a note in the synopsis. Adult works redirect to a
  Naver login and raise an error.

## Cloudflare and blocks

- **Solving challenges**: the fork already ships what CloudStream uses.
  `network/CloudflareKiller` is an OkHttp interceptor that detects a challenge (403/429/503 with
  Cloudflare headers or a challenge body), loads the URL in a hidden WebView through
  `network/WebViewResolver` until a `cf_clearance` cookie appears, caches the cookies per host
  for the session, and replays the request with the WebView's user agent and client hints. A
  provider opts in with `override val usesCloudFlareKiller = true` (its `app` then goes through
  `MainActivity.appWithInterceptor`, and posters get the matching header). Nine existing
  (non-CJK) providers use it. None of the five new sources needs `cf_clearance`, so none of them opts in.
- **Known false positive**: `CloudflareKiller.looksLikeCloudflareChallenge` returns true for *any*
  response whose first 10 KB contains `/cdn-cgi/`, even a 200. Sites that embed Cloudflare's passive
  bot script near the top of short pages (piaotia does this on every page) would trigger a
  WebView solve on each request. That is why Piaotia stays on the plain client.
- **69shuba**: the interceptor could help with the managed challenge that appears after bursts of
  chapter requests (GETs). It would not help with the Turnstile on the search POST, because the
  WebView reloads the URL as a GET; `BiqugeProvider` already runs that search in its own WebView.
  `BiqugeProvider` was not changed.
- **Readable errors**: `providers/SourceGuard` classifies responses as a Cloudflare challenge,
  a Cloudflare ban (1006/1009/1020), rate limiting (429), a CAPTCHA or a plain 403. The new
  providers throw `ErrorLoadingException` with a message that names the site and says what to
  do, instead of returning an empty list or a chapter of junk.

## Evaluated and not added

| Candidate | Result |
|---|---|
| Hameln (syosetu.org), ja | Cloudflare managed challenge (`cf-mitigated: challenge`) on every page from here. It would need `usesCloudFlareKiller`, and the JVM tests could not verify it. Good next candidate to try on a device. |
| wenku8, zh | Same Cloudflare managed challenge. |
| Novel18 / Noc (syosetu adult), ja | Reachable (HTTP 200; the age gate wants an `over18=yes` cookie), but adult-only, and the existing Syosetu provider (not changed in this pass) has placeholder chapter titles. Skipped. |
| Munpia (문피아), ko | Desktop and mobile are SPAs over `/api/v1/...`. Search and the episode list are plain JSON, but chapter text (`/entries/{id}/content`) is empty without a timestamp signature computed in WebAssembly (`novel_wasm_bg.wasm`). Rejected. |
| Joara (조아라), ko | `api.joara.com` works for lists, but `chapter.joa` answers "로그인 후 이용하시기 바랍니다" (login required) even for free chapters. Rejected. |
| Naver Series / Kakao Page / Qidian VIP | Paid or DRM. Out of scope. |
| uukanshu.cc, hetushu, trxs, xbanxia, linovelib/bilinovel, zh | Reachable (HTTP 200), not evaluated further. Ixdzs and Piaotia already cover the zh web-novel catalogue. |
| xbiquge.so, ttkan, 69shu.com, zh | HTTP 403 from here (non-Cloudflare 403 or redirect to blocked mirror). |
| biquge.com.cn, bqgui.cc, biqukan.cc, shuquge, czbooks, uukanshu.net | Connection failures or timeouts from here. |

## Tests

- Fixture tests: `app/src/test/java/com/lagradost/quicknovel/providers/cjk/*ProviderTest.kt`,
  fixtures under `app/src/test/resources/providers/{ixdzs,piaotia,alphapolis,pixiv,naver}/`. The
  fixtures keep each site's real markup (class names, inline JSON, scripts, broken HTML). Titles,
  names and prose are replaced with placeholders. The Piaotia fixtures are stored GBK-encoded.
- Live tests (one search, one novel, one chapter, and the first main page per source; plus
  pagination checks for Pixiv and Naver) are skipped unless enabled:

  ```
  JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests '*providers.cjk*' -Dlive=1
  ```

  `LIVE=1` in the environment works too. The output (`[live] ...` lines) is in the test report's
  standard output.
