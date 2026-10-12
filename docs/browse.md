# Browse

The Browse tab (between Library and Search, in the bottom bar and the navigation rail) shows the
most popular novels of every source, a combined "All sources" feed, and one page per source with
all of its listings. Everything goes through the provider objects (`MainAPI.loadMainPage`,
`load`, `search`): the source website is never opened in a browser or a WebView for browsing.

Code: `app/src/main/java/com/lagradost/quicknovel/ui/browse/` (home, All sources, loader,
pure rules), `ui/mainpage/` (the per-source page), `translator/MetadataTranslator.kt` +
`MetadataTranslations.kt` (translated titles and synopses).

## How listings map to provider fields

A provider exposes three label/value lists and `loadMainPage(page, mainCategory, orderBy, tag)`:

| Provider field | Browse name | Shown as |
|---|---|---|
| `mainCategories` | Listing | first chip row on the source page; "Popular / New / Completed" in All sources |
| `orderBys` | Order | second chip row (empty for the providers below: their listings already include the order) |
| `tags` | Genre | third chip row |

Rules every provider in the Browse tab follows:

- **Index 0 of each list is the default and the most popular listing.** The Browse home rails
  and the All sources "Popular" feed load `(mainCategories[0], orderBys[0], tags[0])`, page 1.
- Labels are English; genres keep the original name in brackets so a reader of the raws
  recognises them, e.g. `Fantasy (판타지)`.
- A listing value may encode several site parameters (`"popular|completed"`); the provider's
  `listingUrl(...)` (internal, unit-tested in `BrowseListingsTest`) turns it into the URL.

Listings of the CJK providers (verified against the live sites, 2026-10):

| Provider | Listings (first = default) | Genres apply to |
|---|---|---|
| Kakuyomu | Weekly ranking, All-time popular, Completed: weekly ranking, Completed: all-time popular, Latest updates, New works (`/search?order=…&serial_status=completed`) | every listing |
| Novelpia | Free works: top 100 today / this week, Top 100 today / this week / this month, Completed: top 100 this week (`/top100/…`, one page of 100), Free works: most viewed / most recommended / latest updates (`/freestory/…`, paged) | every listing (`main_genre`) |
| Naver Webnovel | Best league: daily ranking / weekly ranking (`/best/ranking`, free column, one page) / most viewed / most favourited / top rated / completed (`finish=true`) / latest updates; Challenge league: daily ranking / new works ranking / most viewed / latest updates | every listing (the site has no all-genre lists; Fantasy is the default) |
| Ixdzs | Popular: all time / this month / today (`/hot/…`), Completed works (`/end/`), In genre: popular / completed, popular / ongoing, popular / latest updates (`/sort/…`) | "In genre" listings only |
| Piaotia | Most viewed this week / this month / of all time, Most recommended this week / of all time, Most favourited (`/booktop…`), Completed works (`articlelist.php?fullflag=1`), New works, Latest updates in genre (`/booksort…`) | "Latest updates in genre" only |
| Alphapolis | 24-hour ranking, Weekly ranking, Monthly ranking, Most favourited, Recently completed, Latest updates, New works (`/novel/index?sort=`) | every listing |
| Pixiv Novels | Weekly ranking: original works, Daily / Weekly / Monthly / Rookie ranking, Popular with male / female readers (`/novel/ranking.php`, from `__NEXT_DATA__`), In tag: newest / oldest (tag search) | "In tag" listings only |

Pixiv's popular-by-bookmarks search needs Premium, so its popularity listings are the public
rankings; the overall rankings are mostly fan fiction, so the original-works ranking is first.
Novelpia PLUS works in the Top 100 only show their free episodes (the free Top 100 comes first).
69shuba, Quanben, Syosetu, JJWXC and czbooks follow the same rules (their listings are maintained
with those providers).

## Browse home

- Sources with `hasMainPage`, grouped by language: the reading languages chosen in the setup
  wizard first (default preferences `reading_languages` or `setup_reading_languages`, a string
  set or comma-separated; absent = no preference), then the remaining CJK languages in the order
  Chinese, Japanese, Korean, then every other language in one "Other sources" group, collapsed.
- Each source card: name, language chip, the name of its default listing, and a horizontal
  "Popular" rail with the first 12 entries. The label under the source name is the provider's default listing: the first label of the first axis that is not a bare "All" (JJWXC's status axis defaults to "All", so its ranking "Popular (积分)" is shown instead), or "Popular" when there is none. Tapping a poster opens the result page
  (`loadResult`), a long press the preview sheet; the card title or "See all" opens the
  source page.
- Layout: full-width cards on phones and the Fold cover screen, two cards per row from 840 dp
  (Fold inner screen), the grid centred and capped at 1200 dp. Posters keep the adaptive
  grid (`AdaptiveAtLeast(PosterMinCellWidth, 3)`): 3 columns on a phone and the cover screen,
  6 on the inner screen.

## Loading, caching and politeness

`BrowseRepository`:

- **Lazy**: a rail loads when its card is first composed, i.e. when it scrolls into view.
- **Cache, two layers** (`ListingCache` in front of `ListingDiskCache`, key = (source, listing,
  page)):
  - Memory: every page loaded stays in memory until the app process ends, shared by the rails,
    the All sources feed and the source pages (opening "See all" shows the rail's page instantly).
  - Disk: every successful load is also written to `cacheDir/browse_listings/` (one JSON file per
    page, named by the SHA-1 of the key; the first line holds the save time and the key). After a
    process restart the first access of a page reads it from disk, so the Browse home shows its
    rails without network. Entries expire after 6 hours; at most 200 pages are kept, the least
    recently used are deleted on each write (a read touches the file, so the order survives a
    restart). Only the fields a listing fills are stored (`DiskListingPage`: title, URL, source,
    poster URL and headers, rating, latest chapter); bump `diskKey`'s `v1` if that changes.
    Unreadable or foreign files count as misses. Android may clear the cache dir at any time.
  - Failures and empty pages are never cached in either layer. Pull to refresh and Retry reload
    bypassing both layers and write the new page through to both.
  - Tested in `ListingCacheTest` (TTL, LRU bound across instances, bypass, no caching of failures
    or empty pages, corrupt files).
- **Politeness**: at most 2 sources load at a time for the rails and 3 for the All sources feed;
  requests for the same page share one network call; each load times out after 25 s (60 s for sources behind the Cloudflare interceptor, whose first request may spend about 30 s clearing the challenge in a hidden WebView); the
  provider's own `rateLimitTime` still applies. A source page the user is looking at is not
  throttled.
- **Never blocking**: a failing source shows "Unavailable right now" with Retry on its own card
  (an empty listing shows a short "Nothing here" with Retry), every other card keeps loading.

## Per-source page

`MainPageFragment` / `MainScreen` / `MainPageViewModel2`:

- Chip rows for Listing / Order / Genre (only lists with more than one entry), the search field
  of the source on top (the bar hides while scrolling down).
- Infinite scroll with the adaptive poster grid (or rows), loading 9 items before the end. A page
  that adds nothing new (sites repeat their last page or return nothing) ends the list.
- Pull to refresh, an empty / error state with Retry, and an inline Retry when a later page fails.
- The chosen listing is remembered per source (default preferences
  `browse_last_listing_<source name>` = `"category,order,tag"`) and restored when the page is
  opened from Browse or from the Search screen's source tiles. Explicit filters (a genre chip on
  the result page) win.

## All sources

The first card on the Browse home. One feed made of every zh / ja / ko source's listing
interleaved by rank: rank 1 of each source, then rank 2 of each, and so on. No cross-site score
is invented; the sites' numbers are not comparable.

- **Filters**: Language (All / Chinese / Japanese / Korean; "All" = the three CJK languages) and
  Listing (Popular / New / Completed). Popular is each source's default listing; New and
  Completed use the source's closest listing (`BrowseCatalog.resolve`: a label with "new" /
  "newest", else "latest" / "recent" / "update"; "completed" / "finished" / 完结 / 완결), from
  `mainCategories` first, then `orderBys`. A source without an equivalent is left out of that
  feed. Shared genres across sources are not offered (genre ids differ per site).
- **Independent sources**: every source loads on its own (3 at a time). The feed waits up to 6 s
  for every source's first page, then shows what it has; a source answering later joins at the
  next round, so nothing on screen moves. A source that fails or times out drops out and the
  feed shows "N sources unavailable".
- **Stable infinite scroll**: rounds are only appended. When a source has placed all its loaded
  items, the feed fetches the next page of exactly that source before placing more rounds (so its
  rank 21 does not land below other sources' rank 40); sources at the end of their list stop
  taking part.
- **De-duplication** (the Chinese aggregators mirror the same catalogue): normalised title (NFKC,
  lower case, letters and digits only, Traditional folded to Simplified with Android's ICU
  transliterator on Android 10+) plus the author when both copies have one. The copy with the most
  chapters (the number in the listing's latest-chapter text) is kept, else the first seen; the
  card shows "+N" for the other sources that have it. Tapping opens the kept copy's result page.
- Each card shows its source as a badge. Each filter combination is kept for the session, pull to
  refresh reloads it.

The rules live in the pure `CombinedBrowse` and `BrowseCatalog` classes
(`BrowseLogicTest`: interleave order, waiting for first pages, failure isolation, top-up
pagination, de-duplication, author matching, script folding, listing resolution, grouping).

## Translated titles and synopses

For zh / ja / ko sources, listings show the English title with the original underneath in a
smaller font (Browse rails, All sources, source pages, search, library and history all use the
same poster title). The preview sheet shows the translated title with the original underneath
and the translated synopsis; the result page shows the translated title and synopsis with a
small "Show original" / "Show translation" toggle right above the synopsis (it switches the
title, synopsis, latest chapter and tags, see [result-page.md](result-page.md#translated-metadata)). Rendering
never waits: the original is shown until the translation arrives.

**Engine** (`MetadataTranslations.currentPolicy`):

1. The configured translation model through the OpenAI-compatible `TranslationEngine`, when
   Settings > AI Translation > "Translate titles and synopses with AI" is on (default on;
   default preferences `translate_metadata_with_ai`), an API key is set and the monthly spend cap
   is not reached. Calls are recorded in the spend ledger as kind `metadata`.
2. Otherwise the free Google endpoint, when "Auto-translate titles & synopses" is on. Google is
   also the fallback when an AI call fails.
3. Neither: originals only (cached translations are not shown either).

**Batching** (`MetadataTranslator`, pure, `MetadataTranslatorTest`):

- Titles: each visible poster asks for its title; requests are collected for 150 ms per source
  language and sent as one AI call of at most 20 titles (`{"titles": [...]}` in, the same count
  out), so a screen of posters is one small request per language (a mixed All sources screen is
  one per language). Requests for the same title share one result.
- Synopsis: only when a novel's preview sheet or result page is opened, together with its title
  in one call (`{"title", "synopsis"}`; synopses are cut at 4000 characters). About $0.0002 per
  novel with gpt-6-luna.

**Cache**: table `novel_metadata_translations` in the translator database (version 7, additive
migration 6 to 7, checked against Room's generated schema in `TranslatorDatabaseMigrationTest`):
`novel_id` (`apiName/novelName`), `source_lang`, `title_hash`, `translated_title`,
`title_engine`, `synopsis_hash`, `translated_synopsis`, `synopsis_engine`, `model`,
`updated_at`. Title and synopsis are hashed separately (`SourceHash`) because listings only know
the title. Rows are kept forever and re-translated only when the hash of the original changes;
the one exception is a Google translation, which is redone once per session when AI is
available. A text that failed with every engine is not retried in the same session. Deleting a
novel's translations keeps these rows (they are tiny, paid for, and shown again in Browse).

## Limitations

- The disk cache is per (source, listing, page) with a fixed 6 h TTL; a listing that changes faster
  (latest updates) can look up to 6 hours old after a restart until pulled to refresh.
- Top 100 / ranking pages (Novelpia, Naver) have a single page; the list ends there.
- The All sources feed covers zh / ja / ko sources; other languages are browsed per source.
- De-duplication only compares title (and author when listed); different novels with the same
  title and no author in the listing are merged. Chapter counts are rarely in listings, so the
  first copy seen is usually kept.
- The Traditional to Simplified fold needs Android 10+ (older versions compare as written).
