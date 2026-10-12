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
| Quanben | zh | yes | yes (genres; no rankings on the site) | yes | no | none seen | `QuanbenProvider` |
| **Ixdzs (爱下电子书)** | zh | yes | yes | yes | no | none; own token check on chapter pages (handled) | `chinese/IxdzsProvider` |
| **Piaotia (飘天文学)** | zh | yes | yes | yes | no | Cloudflare passive script only, no challenge; interceptor on as a safety net (2026-10-12) | `chinese/PiaotiaProvider` |
| Kakuyomu (カクヨム) | ja | yes | yes | yes | no | none | `japanese/KakuyomuProvider` |
| Syosetu (小説家になろう) | ja | yes | yes | yes | no | none | `SyosetuProvider` |
| **Alphapolis (アルファポリス)** | ja | yes | yes | yes (free episodes) | no | none; reCAPTCHA if it suspects a bot | `japanese/AlphapolisProvider` |
| **Hameln (ハーメルン)** | ja | yes | yes (rankings, latest updates) | yes | no | Cloudflare managed challenge on every request; cleared by the WebView interceptor (device only) | `japanese/HamelnProvider` |
| **Pixiv Novels (pixiv小説)** | ja | yes | yes (by tag) | yes (all-ages works) | no | none | `japanese/PixivNovelProvider` |
| Novelpia (노벨피아) | ko | yes | yes | yes (free episodes) | no | none | `korean/NovelpiaProvider` |
| **Naver Webnovel (네이버 웹소설)** | ko | yes | yes | yes (best/challenge leagues) | no | none | `korean/NaverWebNovelProvider` |
| **czbooks (小說狂人)** | zh (Traditional) | yes | yes (rankings, per-genre latest/completed) | yes | no | Cloudflare `__cf_bm` + rocket-loader only, no challenge | `chinese/CzbooksProvider` |

Bold rows were added in this pass. Each one has fixture tests and a gated live test (below).

### Status mapping

`providers/CjkStatus.parse(text)` maps the status words the sites print to the app's
`ReleaseStatus` (the generic `setStatus(String)` only knows English words, so CJK text left the
status empty). Substring match, checked in this order:

| Text | Status |
|---|---|
| 未完结, 未完結, 未完本, 미완결 | Ongoing |
| 已完结, 完结, 已完本, 完本, 全本, 已完成, 完結 (完結済), 완결 | Completed |
| 断更, 停更, 休載, 休止, 휴재 | Paused |
| 连载 (连载中), 連載 (連載中), 연재 (연재중) | Ongoing |

Used by 69shuba, Quanben, Syosetu and Hameln. Pass the status field only, not a whole page (全本小说网 is a
site name).

### Romance listings (not offered)

Romance, BL / danmei, GL / yuri / 百合, "no romance" (无CP) and sexual-orientation listings are
deliberately left out of every source's browse axes (2026-10-12), and romance-focused sources
are not carried (JJWXC and Wattpad were removed, see "Removed sources"). Search still finds any
work by name. `providers/cjk/NoRomanceListingsTest` scans the labels and values of every
registered CJK provider so none comes back.

| Source | Left out |
|---|---|
| 69shuba | Romance 言情 (3) |
| Quanben | 言情, 现言 (modern romance), 耽美, and 穿越 / 青春, which on quanben.io list only romances (palace, CEO, campus) |
| Ixdzs | 言情穿越 (7), 耽美同人 (8), 台言古言 (9) |
| Piaotia | nothing: 都市言情 (3) is kept as "Urban (都市)", because the list is mostly urban fiction (the site's completed list calls it 全本都市) |
| czbooks | 言情, 耽美, 百合, and 女性向 / 女生同人 (both list only romance and adult romance); the adult 情色工口 / 耽美工口 were already out |
| Syosetu | Isekai romance (i1), 異世界〔恋愛〕 (101), 現実世界〔恋愛〕 (102) |
| Kakuyomu | 恋愛 (love_story), ラブコメ (romance) |
| Alphapolis | 恋愛 (110500), BL (119000) |
| Pixiv Novels | tags 恋愛, 悪役令嬢 (villainess, a romance genre), BL, 百合 |
| Novelpia | 로맨스 (6) |
| Naver Webnovel | 로맨스 (101), 로판 / romance fantasy (109) |
| Hameln | nothing (its only scope axis is original works / fan fiction) |

The rankings themselves (e.g. a site-wide weekly ranking) are not filtered, so a romance work
can still appear in an all-genre list.

## Per-source notes

### 69shuba (Biquge), zh
GBK, Cloudflare. Plain GETs work, but search POSTs need a Turnstile token (the provider runs the
search in a WebView) and Cloudflare challenges after about 30 quick chapter fetches (the provider
spaces downloads and pauses in bursts). curl gets HTTP 403 from this machine, but the app's
OkHttp client gets through (fixtures and live checks use it).
- Browse (re-done 2026-10-11; the old `/paihang/...` and `/novels/class/{tag}/{order}/{page}/`
  URLs return 404): rankings `/novels/{order}_{category}_{status}_{page}.htm`, 50 per page.
  Listings (`mainCategories`, value `order|status`, no `orderBys`; changed 2026-10-12 so the
  Browse rail is labelled with the default ranking instead of "All"): Popular, monthly (人气榜,
  `monthvisit|0`, default), Popular, weekly (周榜, `weekvisit|0`), Recommended (推荐榜, `allvote|0`),
  Ongoing: popular, monthly (连载人气榜, `monthvisit|2`), Completed: popular, monthly (全本人气榜,
  `monthvisit|1`), Completed: recommended (全本推荐榜, `allvote|1`), Latest updates (最近更新,
  `/last.html`: one page of 30 books, page 2 is empty, ignores genre and status). Tags: the site's
  category ids (`/novels/class/{id}.htm`) except romance (言情, 3). The old shape (status in the main category, ranking
  in `orderBy`) still maps to the same URLs.
- Not offered: 新书榜 (`newhot`) answers with a Cloudflare managed challenge outside a browser;
  `lastupdate`/`allvisit`/`goodnum`/`postdate`/`dayvisit` rankings are empty. The site has no
  monthly-ticket (月票) or bookmark (收藏) ranking.
- Status from `og:novel:status` (连载 / 全本 / 完本) through `CjkStatus`. Tags: the category followed by
  the `tags: '...|...'` list of the page's `bookinfo` script (the visible tag list is filled by JS).

### Quanben, zh — https://www.quanben.io
- Chapter list: the AMP list page `/amp/n/{slug}/list.html` has every chapter on one page (688 for
  斗罗大陆, 2451 for the longest seen). Names and URLs are taken from the same link. Until
  2026-10 the provider took names from the quanben5.com mirror (610 entries for 斗罗大陆) and
  numbered quanben.io URLs in list order, so names and chapters drifted apart. The regular
  `list.html` shows only the first and last ~24 chapters and loads the rest through a signed
  JSONP call; it is used only as a fallback when it is complete.
- Status from `og:novel:status` (完结) through `CjkStatus`.
- Browse: no ranking or latest-updates page exists. Default "Recommended (推荐小说)" is the home
  page (18 books, one page). Genre listings `/c/{genre}.html`, `/c/{genre}_{page}.html` (12 per
  page, site order). Every book on the site is complete (全本), so there is no status filter.

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
- Cloudflare only injects its passive `/cdn-cgi/challenge-platform/scripts/jsd/` script. Since
  2026-10-12 the provider sets `usesCloudFlareKiller`, so a real challenge would be cleared in a
  WebView. Checked on an emulator (Android 16): the Browse rail, the source page listing, a search
  (`NBA`), a novel page (1851 chapters) and a chapter all loaded with no WebView solve started
  (no `CloudflareKiller` log lines). The JVM live test runs the provider with the interceptor off,
  because the interceptor needs Android's `CookieManager`.

### Hameln (ハーメルン), ja — https://syosetu.org
- User-generated novels, mostly fan fiction (二次創作) plus original works. The R-18 section is a
  separate host (h.syosetu.org) that the provider never requests; cards and works tagged R-18
  are dropped, and a work that redirects off syosetu.org raises an error.
- **Cloudflare**: every request without a `cf_clearance` cookie gets a managed challenge (HTTP
  403, `cf-mitigated: challenge`, `cType: 'managed'`; recorded as
  `cloudflare/challenge_hameln.html`). Mobile Chrome on the emulator passed it without a click; a
  desktop automation browser got the interactive "Verify you are human" checkbox instead (not
  clicked). The provider sets `usesCloudFlareKiller`: on the emulator the hidden WebView cleared
  the challenge on its own (Turnstile ran, `cf_clearance` set), and later requests went straight
  through with the cached cookie. **The first solve took about 24 s** and the Browse source page
  gives up after 25 s, so the very first load showed "did not answer in 25 s"; Retry and
  everything after it (rails, search, novel, chapters) worked at normal speed. The JVM client can
  never pass the challenge, so the live test probes the site and skips with a message.
- The interceptor replays requests with the WebView's user agent, so the site serves its
  **smartphone layout**, which is what the parser reads (the PC layout is not parsed). Fixtures
  were recorded through the emulator's Chrome (same-origin `fetch` over DevTools).
- Search `GET /search/?mode=search&word=<q>&type=28` (総合評価 order, 10 a page; first page only).
- Novel `GET /novel/{id}/`: title, author, genre/原作 and tags, synopsis, episode list
  (`li.episode-list__item`; section headings `li > strong` give "section - title"). One-shots
  (短編 with one episode) have no list and become one chapter on the novel page. The info page
  `GET /?mode=ss_detail&nid={id}` (one more request) gives status (話数: 連載(連載中) → Ongoing,
  連載(完結) → Completed, 短編 → Completed), rating (調整平均, 0–10) and views (UA).
- Chapter `GET /novel/{id}/{n}.html`: `#honbun > p`, ruby flattened like Kakuyomu. The author's
  前書き/後書き are not included.
- Browse: `mainCategories` = Daily (日間, default), Weekly, Monthly, Quarterly, Yearly, All-time
  (累計), New works daily (新作日間) and Completed all-time (完結累計) rankings
  (`/?mode=rank_{scope_}{period}`), and Latest updates (最終更新, `/search/?mode=search&type=45`,
  10 a page). `tags` = scope: All (総合), Original works (オリジナル: `rank_ori_…`,
  `gensaku=原作：オリジナル`), Fan fiction (二次創作: `rank_2_…`, `gensaku=原作：二次創作`). A ranking
  is one long page (300 / 100 / 50 entries); the provider fetches it once, caches it for 10 minutes
  and serves 50 a page.
- Politeness: browser user agent, `Referer` (the novel page for chapters), at least 1 s between
  any two requests (provider-wide gate) and `rateLimitTime` 1 s for downloads.
- Verified on emulator-5562 (2026-10-12): Browse rail and source page (daily ranking; Completed +
  Original works; Latest updates with paging), search (`BLEACH`), novel page (33 chapters with
  section titles, status Ongoing, tags, rating, views), chapter text.

### Kakuyomu (カクヨム), ja
Next.js Apollo state. All episodes are embedded on the work page. No login is needed.

### Syosetu (小説家になろう), ja
- Episode titles: the table of contents lists 100 episodes per page (`?p=2`, ...). The provider
  reads every page (250 ms apart) and uses the real episode titles. A page that fails twice gets
  "Episode N" placeholders so later numbering stays right. One-shots (短編) have no episode list
  and become a single chapter on the work page.
- Status and tags come from the info page `/novelview/infotop/ncode/{ncode}/` (one more request):
  `p-infotop-type__type--completed` 完結済 → Completed, `--serialized` 連載中 → Ongoing, `--short`
  短編 → Completed; tags are the genre (ジャンル) and the keywords (キーワード). The novel page no
  longer has a type label, so before 2026-10 every work showed as Ongoing with no tags.
- Browse: main category = genre, with "All genres (総合)" first (`/rank/list/type/{period}_total/`),
  then the isekai lists and the genres (without the romance ones). Orders: All-time ranking (累計, default), Daily (日間),
  Weekly (週間), Monthly (月間), Quarterly (四半期), Yearly (年間), all 50 per page, and Latest
  updates (最新掲載: `/search.php?order=new`, 20 per page; isekai categories map to `istt=1` plus the
  big genre). Tags = status: All, Ongoing (連載中, `_r` / `type=r`), Completed (完結済, `_er` /
  `type=er`), Short stories (短編, `_t` / `type=t`).
- The adult sister sites (Noc/Novel18, which need an `over18=yes` cookie) are not covered.

### Alphapolis (アルファポリス), ja — https://www.alphapolis.co.jp
- Search `/search?query=&category=novel`. Browse `/novel/index?sort=&category_ids=` (24h,
  weekly, monthly points, recent, completed, favourites; 12 categories plus All, without 恋愛 and BL).
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

### czbooks (小說狂人), zh (Traditional Chinese) — https://czbooks.net
- Taiwanese aggregator. **Text is Traditional Chinese**; the source still declares `lang = "zh"`,
  because the translator handles Traditional and Simplified alike. Searching with Simplified
  characters works (the site converts the query).
- UTF-8, simple server-rendered HTML, no login. Cloudflare is in front but only sets `__cf_bm` and
  injects rocket-loader; plain requests with a browser UA get the real pages, so the provider
  does not opt into `usesCloudFlareKiller` (the `/cdn-cgi/` false positive would trigger on every
  page). (It timed out from here in an earlier check; it was reachable on 2026-10-12.)
- Search `GET /s/<q>` (first page). Novel `GET /n/{id}`: title, author, cover, 連載狀態, synopsis,
  category + the first 12 reader hashtags as tags, and the whole `ul#chapter-list` in reading
  order. Chapter `GET /n/{id}/{cid}?chapterNumber=n`, text in `div.content`. Chapter 0 is often
  the "作品相關" intro page.
- Browse: `orderBys` (default first) Popular this week (週排行), Popular today (日排行), Popular all
  time (總排行), Latest updates (最新), Completed (完本); `tags`: All genres plus 12 genres (no romance ones). With
  All genres, Popular uses the site's 精選排行 `/c/normal/{day|week|total}/{page}` (the homepage's
  main ranking; the 人氣榜 `/c/all` mixes in adult titles) and Completed uses the 完本榜
  `/c/finish/week/{page}`, which can include adult titles. With a genre it is
  `/c/{genre}/{day|week|total|new|finish}/{page}`. The site has no all-genre latest list
  (`/c/all/new` silently shows the daily ranking), so Latest with All genres raises "pick a genre".
  The two adult categories (情色工口, 耽美工口) and 工口榜 are not offered. 70 cards a page.
- Paging: a page past the end comes back as some other page instead of an empty one, so the
  provider checks the active link in `ul.paginate` and returns an empty page when it does not
  match.

## Cloudflare and blocks

- **Solving challenges**: the fork already ships what CloudStream uses.
  `network/CloudflareKiller` is an OkHttp interceptor that detects a challenge (403/429/503 with
  Cloudflare headers or a challenge body), loads the URL in a hidden WebView through
  `network/WebViewResolver` until a `cf_clearance` cookie appears, caches the cookies per host
  for the session, and replays the request with the WebView's user agent and client hints. A
  provider opts in with `override val usesCloudFlareKiller = true` (its `app` then goes through
  `MainActivity.appWithInterceptor`, and posters get the matching header). Nine existing
  (non-CJK) providers use it, plus Hameln (which needs it) and Piaotia (as a safety net). The
  other CJK sources (including czbooks) never get a challenge and do not opt in. The
  interceptor cannot run in JVM tests (it needs `CookieManager`); the live tests of opted-in CJK
  providers run with it off (Piaotia) or skip on a challenge (Hameln).
- **Challenge detection** (`CloudflareKiller.isCloudflareChallenge`, fixed 2026-10): a response
  counts as a challenge only with `cf-mitigated: challenge`, a redirect into `/cdn-cgi/`, challenge
  markup (`_cf_chl_opt`, `__cf_chl_`, `cf-chl-`, `/cdn-cgi/challenge-platform/h/`,
  `cf-browser-verification`, `challenge-form`, a "Just a moment..." / "One moment" / "Un momento"
  `<title>`, the old IUAM `jschl` fields), or a Turnstile form on a 403/429/503 (or on a short
  Cloudflare page). Before, any response with `/cdn-cgi/` in its first 10 KB counted, including
  normal 200 pages with Cloudflare's passive `/cdn-cgi/challenge-platform/scripts/jsd/` script or
  email protection, and any Cloudflare 403/429/503 (a firewall ban or a plain 403, which a WebView
  cannot clear). The cookie cache is unchanged. Tested on recorded 69shuba and Hameln challenges,
  a recorded piaotia chapter and a Hameln novel page (`CloudflareKillerTest`).
- **Piaotia** uses the interceptor since 2026-10-12. It shows no challenge (only the passive
  script); on the emulator no WebView solve was started for any Piaotia request.
- **Hameln** needs the interceptor (managed challenge on every request). On the emulator the
  hidden WebView cleared it without interaction in about 24 s (challenge page, Turnstile, reload,
  then the resolver's 5 s cookie-stability wait); see the Hameln notes for the first-load timeout.
  If Cloudflare ever shows WebViews the interactive checkbox, the solve times out after 60 s and
  the provider shows the "Cloudflare browser check" error.
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
| wenku8, zh | Same Cloudflare managed challenge. |
| Novel18 / Noc (syosetu adult), ja | Reachable (HTTP 200; the age gate wants an `over18=yes` cookie), but adult-only. Skipped. |
| Munpia (문피아), ko | Desktop and mobile are SPAs over `/api/v1/...`. Search and the episode list are plain JSON, but chapter text (`/entries/{id}/content`) is empty without a timestamp signature computed in WebAssembly (`novel_wasm_bg.wasm`). Rejected. |
| Joara (조아라), ko | `api.joara.com` works for lists, but `chapter.joa` answers "로그인 후 이용하시기 바랍니다" (login required) even for free chapters. Rejected. |
| Naver Series / Kakao Page / Qidian VIP | Paid or DRM. Out of scope. |
| uukanshu.cc, hetushu, trxs, xbanxia, linovelib/bilinovel, zh | Reachable (HTTP 200), not evaluated further. Ixdzs and Piaotia already cover the zh web-novel catalogue. |
| xbiquge.so, ttkan, 69shu.com, zh | HTTP 403 from here (non-Cloudflare 403 or redirect to blocked mirror). |
| biquge.com.cn, bqgui.cc, biqukan.cc, shuquge, uukanshu.net | Connection failures or timeouts from here. (czbooks timed out too in that check; it was reachable later and is now a source.) |

## Removed sources

The fork keeps the raw CJK sources above (translated by its own AI translator) plus
human-translation and original-fiction sites (e.g. Royal Road, Scribble Hub, Wuxiaworld, fan
translation groups, ebook libraries). Machine-translation (MTL) aggregators are not carried:
reading the raw source through the fork's translator replaces them. Romance-focused sources are
not carried either (see "Romance listings (not offered)" above). `providers/RemovedSourcesTest`
and `providers/cjk/NoRomanceListingsTest` check that none is registered again.

| Source | Removed | Reason |
|---|---|---|
| JJWXC (晋江文学城), zh | 2026-10-12 | A romance / danmei (BL) / GL site (言情, 纯爱, 百合); the user does not read romance. It was a working source (search, 作品库 browse, free chapters; VIP chapters left out). |
| Wattpad, en (upstream provider) | 2026-10-12 | Romance-centric user-generated site; the user does not read romance. `WattpadProvider`, its icon and colour were deleted. |
| LNMTL (lnmtl.com), en | 2026-10-12 | MTL aggregator: MTL site ("Place where Web Novels are being MTLed"). Deleted `LnMTLProvider`, its icon, deep link and README entry. |
| MTLNovel (mtlnovel.me), en | 2026-10-12 | MTL aggregator: MTL site ("Read RAW Machine Translation Novels"). Deleted `MtlNovelProvider`, its icon, deep link and README entry. |
| Sonic MTL (sonicmtl.com), en | 2026-10-12 | MTL aggregator: MTL site ("Machine Translated (MTL) novels"). Deleted `SonicMTLProvider`, its icon, deep link and README entry. |
| WTR-LAB (wtr-lab.com), en | 2026-10-12 | MTL aggregator: MTL site ("English MTL (Machine Translation) Novels", translated from raw). Deleted `WtrLabProvider`, its icon, deep link and README entry. |
| Wuxia Box (wuxiabox.com), en | 2026-10-12 | MTL aggregator: MTL site ("English MTL Novels ... translated from raw"). Deleted `WuxiaBoxProvider`, its icon, deep link and README entry. |
| FanMTL (fanmtl.com), en | 2026-10-12 | MTL aggregator: MTL fan-fiction site on the Wuxia Box engine. Deleted `FanMtlnProvider`, its icon, deep link and README entry. |
| Wuxia Click (wuxia.click), en | 2026-10-12 | MTL aggregator: Aggregator whose entries are labelled MTL. Deleted `WuxiaClickProvider`, its icon, deep link and README entry. |
| Ranovel (ranovel.com), en | 2026-10-12 | MTL aggregator: MTL site ("Read Korean MTL Novel"). Deleted `RanovelProvider` and its icon. |
| MeioNovel (meionovels.com), id | 2026-10-12 | MTL aggregator: Indonesian, chapters labelled "MTL". `MeioNovelProvider` is unregistered and its deep link and README entry removed; the class and icon stay because `ArNoProvider` and `NovLoveProvider` extend it. |
| NovelBuddy (novelbuddy.me), en | 2026-10-12 | MTL aggregator: Aggregator with an MTL section; its most popular list is led by "MTL - ..." entries. Deleted `NovelBuddyProvider`, its icon, deep link and README entry. |
| NoBadNovel (nobadnovel.com), en | 2026-10-12 | MTL aggregator: Aggregator of long-tail recent Chinese web novels in machine-translated English. Deleted `NoBadNovelProvider`, its icon, deep link and README entry. |
| PawRead (pawread.com), en | 2026-10-12 | MTL aggregator: Advertises machine translation of Chinese novels (site keywords). Deleted `PawReadProver`, its icon, deep link and README entry. |

## Tests

- Older sources (69shuba, Quanben, Syosetu, Kakuyomu, Novelpia): `providers/*FixtureTest.kt`, with
  `CjkStatusTest` for the status mapping and `network/CloudflareKillerTest` for challenge detection.
  Gated live checks for them: `CjkProvidersLiveSmokeTest`, `ProviderFixesLiveTest` (Quanben chapter
  names against the chapter pages' headings, Syosetu titles/status/tags, 69shuba status/tags) and
  `BrowseListingsLiveTest` (every browse listing: page 1 non-empty, page 2 brings new works):

  ```
  ./gradlew :app:testDebugUnitTest --tests '*BrowseListingsLiveTest*' --tests '*ProviderFixesLiveTest*' -Dlive=1
  ```
- Fixture tests: `app/src/test/java/com/lagradost/quicknovel/providers/cjk/*ProviderTest.kt`,
  fixtures under `app/src/test/resources/providers/{ixdzs,piaotia,alphapolis,pixiv,naver,czbooks,hameln}/`. The
  fixtures keep each site's real markup (class names, inline JSON, scripts, broken HTML). Titles,
  names and prose are replaced with placeholders. The Piaotia fixtures are stored GBK-encoded.
- Live tests (one search, one novel, one chapter, and the first main page per source; plus
  pagination checks for Pixiv and Naver, and page 1 vs page 2 of each Browse list for
  czbooks) are skipped unless enabled:

  ```
  JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest --tests '*providers.cjk*' -Dlive=1
  ```

  `LIVE=1` in the environment works too. The output (`[live] ...` lines) is in the test report's
  standard output.
