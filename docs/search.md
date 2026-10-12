# Search

The Search tab searches every enabled source at once. A row of language chips under the search
field limits which sources are searched. For Chinese, Japanese and Korean sources, the query is
also translated into a short list of candidate queries, so an English title can find the raw novel
under its original name.

Code: `app/src/main/java/com/lagradost/quicknovel/ui/search/` (`SearchScreen.kt` is the live
Compose screen, `HomeViewModel2.kt` its view model, `SearchLanguage.kt` and `CandidateSearch.kt`
the pure rules), `translator/SearchQueryTranslator.kt` (candidates), `translator/NovelUpdatesIndex.kt`
(title index). `SearchViewModel.kt` and its adapters are the old XML screen. Nothing creates them
any more; they still call `SearchQueryTranslator.translateQuery` (first candidate only).

## Language chips

All languages / Chinese / Japanese / Korean / English, in the same Compose `FilterChip` style as
Browse's All sources page. The row scrolls sideways when the window is too narrow (phone and Fold
cover widths). On wide windows it sits under the search field, centred with it.

| Chip | Sources searched | Query sent to them |
|---|---|---|
| All languages | every enabled source | each CJK source gets candidates in its own language; English and other sources get the raw query |
| Chinese / Japanese / Korean | sources whose `MainAPI.lang` has that base language (`zh-CN` counts as Chinese) | candidates in that language only |
| English | sources whose lang is `en` or unset (the upstream default of `MainAPI.lang`) | the raw query, never translated |

- Other languages (ar, es, fr, id, pt, ru) are only searched under All languages.
- The user's settings still apply on top: Settings → Search providers (per source) and Provider
  languages.
- The chip also filters the source tiles shown before a search.
- The selection is stored in the default SharedPreferences under `search_language` (`all`, `zh`,
  `ja`, `ko`, `en`; default `all`). It is restored when the tab opens.
- Changing the chip while results are open re-runs the last query.
- If no enabled source has the chosen language, a notice says so.
- Results: with All languages, the raw CJK sources' sections come first, then English sites, then
  other languages. Sections with results come before empty or failed ones.

## Candidate queries

`SearchQueryTranslator.candidatesFor(query, lang)` returns the queries a CJK source tries, in this
order:

1. **Known original title (AI).** Only when "Use AI for search queries" is on and an API key is
   set. The **Title lookup model** setting chooses the model (Settings → AI translation → Titles,
   synopses and search). It is separate from the translation model and defaults to GPT-6 Sol.
   Without a key for that model's provider, the translation model is used. The search prompt asks
   for up to three lines: the published original title if the model knows the novel, then a
   literal translation, then a common alternative wording. A reply of one line (the old contract)
   still works. Numbering, labels and quotes are stripped.
2. **NovelUpdates associated names.** No key needed. For a Latin-script query, NovelUpdates is
   searched and the top series page is opened. Its "Associated Names" block lists the original
   titles. A name goes to a language by its script: Hangul to Korean, kana to Japanese, Han only to
   Chinese and Japanese (Japanese prefers names with kana). At most 3 names per language.
3. **Literal translation** from the free Google endpoint.
4. The model's other lines (literal, alternative).

A query already written in the target script (Han for Chinese, kana or Han for Japanese, Hangul for
Korean) is sent unchanged, and nothing is looked up. The translated part (model and Google) gives at
most 3 candidates. Duplicates and echoes of the query are dropped. If nothing is left, the original
query is used. "Translate search queries" off means the raw query for every source.

Each CJK source tries its candidates one at a time, with the source's `rateLimitTime` between
requests, and stops at the first candidate that returns results. Results are de-duplicated by URL.
A failing candidate does not stop the others. The section shows an error only when every candidate
failed. Logcat tag `SearchLanguage` logs each source's tried candidates and which one answered.

**Cost and caching.** The AI path makes one short call per language per query (search kind in the
spend ledger, a fraction of a cent). Candidates are cached in memory per (language, query), and
concurrent lookups share one request. NovelUpdates answers are cached in memory per query. A
failure is remembered for 2 minutes so that the zh, ja and ko lookups of one search do not repeat
it.

## NovelUpdates title index

`NovelUpdatesIndex` is a lookup index only. It is never registered as a provider and never shown
as a result.

- Browser user agent; at most 3 page fetches per query (the search page, then series pages);
  1 s between requests; 25 s overall timeout.
- On the device the requests go through `MainActivity.appWithInterceptor` (the `CloudflareKiller`
  client). A solvable "Just a moment" challenge is cleared by the hidden WebView, as for providers
  with `usesCloudFlareKiller`.
- A firewall block (1006/1009/1020, "Sorry, you have been blocked"), a challenge that stays, any
  other HTTP error or an exception gives up quietly with no names (`SourceGuard.classify`).
- `NovelUpdatesParser` is pure: search hits (exact title match opened first), series title,
  associated names. `NovelUpdatesIndexTest` runs it on synthetic fixtures in
  `src/test/resources/novelupdates/`. The fixtures were written by hand in the site's markup,
  because the site blocks the development machine (see Limits).

## Limits

- **Without a key, the raw-source bridge depends on NovelUpdates being reachable.** On
  2026-10-12, NovelUpdates answered the development machine's network with a Cloudflare firewall
  block (HTTP 403 "Sorry, you have been blocked"), from plain HTTP and from a real browser. The
  block is IP-based, so a phone on another network may get through, or may get a challenge the
  WebView can solve. Where it is blocked, a key-free search sends only the Google literal
  translation. A literal never matches a published title: "Above the sky" becomes 天空之上, not
  高天之上.
- **With a key, the AI known-title path is primary,** but it only helps when the model knows the
  novel. Live check on 2026-10-12 for "Above the sky":
  - GPT-6 Luna (the translation default), first prompt: 天空之上, 苍穹之上. No original title.
  - GPT-6 Sol, prompt with the hint that English queries are often translation titles of CJK web
    novels: 高天之上, 天空之上, 苍穹之上.

  That is why the title lookup model is a separate setting and defaults to GPT-6 Sol. The prompt
  is a few lines and the answer is cached, so the stronger model still costs well under a cent per
  search.
- The NovelUpdates fixtures are synthetic. If the site changes its markup (`.search_title a`,
  `#editassociated`, `.seriestitlenu`), the parser test will not notice.

## Tests

- `ui/search/SearchLanguageTest`: source filter per chip, translation target per chip, preference
  default.
- `ui/search/CandidateSearchTest`: stop at the first answer, URL de-duplication, errors, section
  order.
- `translator/SearchQueryCandidatesTest`: model reply parsing, merge order, key-free and AI paths
  with fake sources.
- `translator/NovelUpdatesIndexTest`: parser on fixtures, script languages, fetch budget,
  block/challenge/error handling.
- `translator/TitleLookupModelTest`: default (GPT-6 Sol), "same as the translation model", unknown
  ids.
- `translator/SearchCandidatesLiveTest`, run with `-Dlive=1`: AI candidates for "Above the sky"
  must contain 高天之上. It uses the title lookup default; `-Dlive.searchModel=<id>` overrides it. It reads `OPENAI_API_KEY` from the environment or the repo-root
  `bench.local.properties`, like the benchmark, and skips without a key. NovelUpdates names must
  contain 高天之上; that test skips when NovelUpdates blocks or challenges the network. The key
  is never printed.
