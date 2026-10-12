# Provider fixtures

Recorded pages for the JVM provider tests in `app/src/test/java/com/lagradost/quicknovel/providers/`
(`*FixtureTest`, loaded through `ProviderFixtures`). One folder per provider:

| Folder | Provider | Pages (recorded 2026-10) |
|---|---|---|
| `69shuba/` | `BiqugeProvider` | `booklist.html` (category listing; same `div.newbox` markup as search results), `rank.html` (`/novels/monthvisit_0_0_1.htm`, the default browse ranking, five entries), `latest.html` (`/last.html`), `info.html` (with the `bookinfo` tag script) + `catalog.html` (book 90435), `chapter.html` |
| `quanben/` | `QuanbenProvider` | `search.html` (`斗罗`), `novel.html` (douluodalu), `amp_list.html` (AMP chapter list, all 688 chapters), `list.html` (regular list with the collapsed middle), `home.html`, `category.html` + `category_page2.html` (`/c/xuanhuan.html`, `_2.html`), `chapter.html` |
| `syosetu/` | `SyosetuProvider` | `search.html` (`転生`), `novel.html` + `novel_page2.html` + `novel_last_page.html` (n9669bk, table of contents pages 1-3), `infotop.html` (n9669bk, 完結済) + `infotop_serialized.html` (n3159fs, 連載中), `oneshot.html` (n7399mv, 短編, trimmed), `rank.html` + `rank_page2.html` (all-genre all-time ranking, five entries each), `chapter.html` |
| `cloudflare/` | `CloudflareKiller` | `challenge_69shuba.html`: the managed challenge 69shuba served for `/novels/newhot_0_0_1.htm` (HTTP 403, `cf-mitigated: challenge`); `challenge_hameln.html`: the managed challenge (`cType: 'managed'`) syosetu.org answers every JVM request with (HTTP 403, `cf-mitigated: challenge`, recorded 2026-10-12) |
| `kakuyomu/` | `KakuyomuProvider` | `search.html` (`転生`; `totalCharacterCount` of each work added 2026-10-12 from the live page, which the first trim had dropped), `work.html`, `episode.html` |
| `royalroad/` | `RoyalRoadProvider` | `best_rated.html` (`/fictions/best-rated`), `search_pages.html` (`/fictions/search?minPages=500&maxPages=1500&orderBy=popularity`), `latest_updates.html` (`/fictions/latest-updates`, first two chapters per card), recorded 2026-10-12: first three cards each, synopses removed |
| `novelpia/` | `NovelpiaProvider` | `search.json` (`회귀`), `novel.html` (26536), `episode_list.html` (page 0), `viewer.json` |
| `czbooks/` | `CzbooksProvider` | `search.html` (`难哄`), `novel.html` (shhmoa, chapter list trimmed), `chapter.html`, `list_popular.html` (`/c/normal/week/1`), `list_latest.html` (`/c/yanqing/new/2`), `list_completed.html` (`/c/finish/week/1`, one adult-titled card removed) |
| `hameln/` | `HamelnProvider` | Smartphone layout (what the Cloudflare WebView user agent gets), fetched 2026-10-12 through a mobile Chrome that had passed Cloudflare, because the JVM client only ever gets the challenge. `rank.html` (`?mode=rank_day`, six cards; the fifth card's title and tags were edited to an R-18 test card), `search.html` (`転生`, `type=28`, five cards), `latest.html` (`type=45`, three cards), `search_empty.html`, `novel.html` (425756, 33 episodes in two sections), `detail.html` (`?mode=ss_detail`, 連載中), `chapter.html` (first 40 lines), `oneshot.html` + `oneshot_detail.html` (428992, 短編 1話, text on the novel page). Recommendation, search-board and similar-works boxes were removed |

`synthetic_*` files are small hand-written pages (structure copied from the sites, text
invented) that pin edge cases such as adult-work filtering, locked episodes, watermarks and
non-kana ruby (and, since the length filter, the length markup copied from the live pages: Novelpia "N회차" / "EP.N", Pixiv `character_count`); they are used by `ProviderParserTest`.

## No copyrighted prose

Chapter text, synopses and blurbs in these files are **not** the original prose. Every CJK
ideograph, kana and Hangul syllable in those regions was replaced, character by character, by a
placeholder of the same script (`甲乙丙丁…`, `あいう…`, `アイウ…`, `가나다…`) with
`app/src/test/tools/scrub_prose.py`. Tags, attributes, entities, punctuation, digits and line
structure are untouched, so the parsers run exactly the same code paths, but the words are gone.
Titles, author names and chapter titles are kept (they are what the tests assert on).
Never commit a chapter page without scrubbing it.

## Trimming

Large pages were reduced so the fixtures stay small: `<style>`, `<svg>` and large inline
`<script>` blocks are dropped, search pages keep their first five results, the Novelpia novel
page keeps only its info block and `og:` meta tags, and the Kakuyomu Next.js `__NEXT_DATA__`
Apollo state keeps only the entries the parser reads (the search connection and its works, or
the work, its table of contents, chapters, episodes and author). Small inline scripts inside the
content (ad slots such as `loadAdv(...)`) are kept on purpose: the tests check they are removed.

## Recording a new fixture

1. Fetch the page. `curl` works for most sites; for 69shuba (Cloudflare refuses curl but serves
   OkHttp) or GBK pages use the recorder, which goes through the app's OkHttp stack and saves
   UTF-8:

   ```
   ./gradlew :app:testDebugUnitTest --tests '*RecordFixture*' \
       -Dlive.record=https://www.69shuba.com/book/90435.htm \
       -Dlive.record.out=69shuba/info.html -Dlive.record.referer=https://www.69shuba.com
   ```

   (`-Dlive.record.post=<form body>` sends a POST.) Pages from curl must be saved as UTF-8.
2. Trim it if it is large (see above) — keep every element the parser selects.
3. Scrub prose before committing, e.g.

   ```
   python app/src/test/tools/scrub_prose.py app/src/test/resources/providers/69shuba/chapter.html \
       --between '<div id="txtright">' '<div class="bottom-ad">'
   python app/src/test/tools/scrub_prose.py app/src/test/resources/providers/novelpia/viewer.json --json-key text
   ```
4. Add assertions in the provider's `*FixtureTest`. NiceHttp's `response.document` is
   `Jsoup.parse(text)` with no base URI, so use `ProviderFixtures.document(path)` for providers
   that use it and `document(path, url)` for providers that call `Jsoup.parse(html, url)`.
