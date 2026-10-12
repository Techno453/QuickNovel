# Search

The Search tab searches every enabled source at once. A row of language chips under the search
field limits which sources are searched. For Chinese, Japanese and Korean sources, the query is
also translated into a short list of candidate queries, so an English title can find the raw novel
under its original name.

Code: `app/src/main/java/com/lagradost/quicknovel/ui/search/` (`SearchScreen.kt` is the live
Compose screen, `HomeViewModel2.kt` its view model, `SearchLanguage.kt` and `CandidateSearch.kt`
the pure rules), `translator/SearchQueryTranslator.kt` (candidates), `translator/TitleIndex.kt`,
`translator/NovelUpdatesIndex.kt` and `translator/NovelBuddyIndex.kt` (title index).
`SearchViewModel.kt` and its adapters are the old XML screen. Nothing creates them any more; they
still call `SearchQueryTranslator.translateQuery` (first candidate only).

## Language chips

All languages / Chinese / Japanese / Korean / English, in the same Compose `FilterChip` style as
Browse's All sources page. The row scrolls sideways when the window is too narrow (phone and Fold
cover widths). On wide windows it sits under the search field, centred with it.

| Chip | Sources searched | Query sent to them |
|---|---|---|
| All languages | every enabled source | each CJK source gets candidates in its own language; English sources get the raw query |
| Chinese / Japanese / Korean | sources whose `MainAPI.lang` has that base language (`zh-CN` counts as Chinese) | candidates in that language only |
| English | sources whose lang is `en` or unset (the upstream default of `MainAPI.lang`) | the raw query, never translated |

- Only en, zh, ja and ko sources exist (the others were removed, docs/sources.md "Removed sources");
  a source in any other language would only be searched under All languages.
- The user's settings still apply on top: Settings → Search providers (per source) and Provider
  languages.
- The chip also filters the source tiles shown before a search.
- The selection is stored in the default SharedPreferences under `search_language` (`all`, `zh`,
  `ja`, `ko`, `en`; default `all`). It is restored when the tab opens.
- Changing the chip while results are open re-runs the last query.
- If no enabled source has the chosen language, a notice says so.
- Results: with All languages, the raw CJK sources' sections come first, then English sites
  (a source in any other language would come last). Sections with results come before empty or
  failed ones.

## Candidate queries

`SearchQueryTranslator.candidatesFor(query, lang)` returns the queries a CJK source tries, in this
order:

1. **Known original title (AI).** Only when "Use AI for search queries" is on (default on since
   3.9.3; it was off before, so the model was never asked) and an API key is set. Without a key
   the step is skipped silently. The **Title lookup model** setting chooses the model (Settings → AI translation → Titles,
   synopses and search). It is separate from the translation model and defaults to GPT-6 Sol.
   Without a key for that model's provider, the translation model is used. The search prompt asks
   for up to three lines: the published original title if the model knows the novel, then a
   literal translation, then a common alternative wording. A reply of one line (the old contract)
   still works. Numbering, labels and quotes are stripped.
2. **Title index (NovelUpdates and Novel Buddy).** No key needed. For a Latin-script query both
   are asked in parallel (`TitleIndex`): NovelUpdates is searched and the top series page's
   "Associated Names" block read; the Novel Buddy search API's entries carry the original title as
   an alternative name (its "MTL - …" entries of the same novel count too), and only entries named
   exactly like the query are used. NovelUpdates names come first. A name goes to a language by its
   script: Hangul to Korean, kana to Japanese, Han only to Chinese and Japanese (Japanese prefers
   names with kana). At most 3 names per language.
3. **Literal translation** from the free Google endpoint.
4. The model's other lines (literal, alternative).

A query already written in the target script (Han for Chinese, kana or Han for Japanese, Hangul for
Korean) is sent unchanged, and nothing is looked up. The translated part (model and Google) gives at
most 3 candidates. Duplicates and echoes of the query are dropped. If nothing is left, the original
query is used. "Translate search queries" off means the raw query for every source.

Each CJK source tries its candidates one at a time, with the source's `rateLimitTime` between
requests (`ui/search/CandidateSearch.kt`). It stops at the first candidate with a **title match**: a
result whose name contains the candidate, ignoring case and whitespace. Sites with fuzzy search
answer almost any query, so a wrong first guess from the title lookup (Sol has answered 天穹之上 for
"Above the sky" in one run and 高天之上 in another) must not hide the right second candidate: a
candidate that returns only non-matching results is kept and the next one is tried, up to
`MAX_ANSWERED` (3) answering candidates. Everything returned is merged, the matching candidate's
results first, de-duplicated by URL. A failing candidate does not stop the others. The section shows
an error only when every candidate failed. Logcat tag `SearchLanguage` logs each source's tried
candidates and which one answered.

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

## Novel Buddy title index

`NovelBuddyIndex` is the second lookup index, added 2026-10-12 because NovelUpdates is blocked on
some networks and the model does not know every title. Novel Buddy itself was removed as a reading
source (an MTL aggregator, docs/sources.md); only its search API is read, as a title directory.

- One request per query: `https://api.novelbuddy.me/titles/search?page=1&limit=10&q=…`, a JSON
  answer whose items carry `name`, `url`, `alt_names[].name` (or `alt_name`), `is_mtl`. Browser
  user agent, plain client (`MainActivity.app`, no Cloudflare seen), 15 s timeout.
- `NovelBuddyParser.namesFor` keeps the CJK alternative names of the entries whose title, with an
  "MTL -" prefix ignored, or whose Latin alternative name equals the query (case and punctuation
  ignored). The other hits of a search are unrelated novels, whatever names they carry.
  Human-translation entries come before MTL entries. Comma-separated names are split.
- Live on 2026-10-12 from the development machine: "Above the sky" → 高天之上 (from the "MTL-above
  the sky" entry; the human-translation entry has no alternative name), "Lord of the Mysteries" →
  诡秘之主 (from "Lord Of Enigma, 诡秘之主").
- Errors, blocks and non-JSON answers give up quietly; `LookupCache` (shared with NovelUpdates)
  caches answers in memory and remembers a failure for 2 minutes.
- `NovelBuddyIndexTest` runs the parser on synthetic fixtures in `src/test/resources/novelbuddy/`
  (real field names, summaries replaced).

## Limits

- **NovelUpdates may be unreachable.** On 2026-10-12, NovelUpdates answered the development
  machine's network with a Cloudflare firewall block (HTTP 403 "Sorry, you have been blocked"),
  from plain HTTP and from a real browser. The block is IP-based, so a phone on another network
  may get through, or may get a challenge the WebView can solve. The Novel Buddy index covers the
  gap; where both fail, a key-free search sends only the Google literal translation, which never
  matches a published title ("Above the sky" becomes 天空之上, not 高天之上).
- **The AI known-title path only helps when the model knows the novel.** Live checks on
  2026-10-12:
  - GPT-6 Luna (the translation default) for "Above the sky": 天空之上, 苍穹之上. No original title.
  - GPT-6 Sol: 高天之上 once, then 天穹之上 / 天之上 / 穹顶之上 in nine runs with three prompt
    variants (temperature 0). For 14 other well-known titles (诡秘之主, 蛊真人, 我欲封天, 放开那个女巫,
    奥术神座, 無職転生, 本好きの下剋上, 月が導く異世界道中, 異修羅, 転生したらスライムだった件, 전지적
    독자 시점, 나 혼자만 레벨업, 소설 속 엑스트라, 백작가의 망나니가 되었다) its first line was the
    original title every time.

  So the title index is what finds the less famous titles, the model the famous ones, and the
  candidate loop (above) makes sure a wrong model guess does not hide an index name. The title
  lookup model stays a separate setting defaulting to GPT-6 Sol: a few lines per search, cached,
  well under a cent.
- **Raw sources answer their own titles.** Live on 2026-10-12 with the original titles: Quanben,
  Ixdzs and czbooks (traditional script on czbooks: 诡秘之主 lists 詭秘之主) found every Chinese
  title; Syosetu, Kakuyomu, Alphapolis and Pixiv found the Japanese titles published on them;
  Novelpia and Naver found the Korean ones (none for 백작가의 망나니가 되었다, a Kakao Page title).
  69shuba search, Piaotia and Hameln need the device's WebView (Cloudflare, Turnstile).
- The NovelUpdates and Novel Buddy fixtures are synthetic. If a site changes its markup
  (`.search_title a`, `#editassociated`, `.seriestitlenu`) or JSON fields, the parser tests will
  not notice; `SearchCandidatesLiveTest` will.

## Tests

- `ui/search/SearchLanguageTest`: source filter per chip, translation target per chip, preference
  default.
- `ui/search/CandidateSearchTest`: stop at the first title match, a wrong first guess does not
  hide the right second candidate, the answering-candidate budget, URL de-duplication, errors,
  section order.
- `translator/SearchQueryCandidatesTest`: model reply parsing, merge order, key-free and AI paths
  with fake sources.
- `translator/NovelUpdatesIndexTest`: parser on fixtures, script languages, fetch budget,
  block/challenge/error handling.
- `translator/NovelBuddyIndexTest`: parser on fixtures, exact-title filter, comma-separated and
  Latin alternative names, lookup errors, cache and CJK-query skip.
- `translator/TitleIndexTest`: merge order of the two indexes, one failing.
- `translator/TitleLookupModelTest`: default (GPT-6 Sol), "same as the translation model", unknown
  ids.
- `translator/SearchCandidatesLiveTest`, run with `-Dlive=1`: the AI candidates for "Above the
  sky" are printed (informational, see Limits). It uses the title lookup default;
  `-Dlive.searchModel=<id>` overrides it. It reads `OPENAI_API_KEY` from the environment or the
  repo-root `bench.local.properties`, like the benchmark, and skips without a key. Novel Buddy
  names must contain 高天之上 (skips when unreachable). NovelUpdates names must contain 高天之上;
  that test skips when NovelUpdates blocks or challenges the network. The key is never printed.
