# Result page

The page a novel opens on (poster, metadata, actions, chapter list). It is a Compose screen,
`ResultFragment2` + `ResultScreen` + `ResultViewModel2` in `ui/result/`, built on upstream's
unfinished Compose page and kept in its state / actions / effects shape.

Every way of opening a novel goes through `MainActivity.loadResult(url, apiName)` →
`navigation_results` (search, Browse rails and the All sources feed, per-source pages, library,
history, downloads, library-update notifications, imported books via `openImportedResult`), and
that destination is `ResultFragment2`.

## Architecture

| Part | Role |
|---|---|
| `ResultFragment2` | Hosts the Compose view in `CloudStreamTheme`; observes the activity's `TranslateViewModel.progress` (Translate button, "Translation finished" snackbar); refreshes the chapter chips every 4 s while started; handles effects that need a `FragmentManager` or navigation. |
| `ResultViewModel2` | Loads the novel: `APIRepository.load` (online) or `ImportedBooks.loadResponse` (imported, no provider). Keeps the raw `LoadResponse` for the downloader and reader and publishes an immutable `ResultState`. Takes `ResultPageAction`s, posts `ResultPageEffect`s. |
| `ResultScreen` | Pure UI of `ResultState`. Holds only scroll positions (`ResultListStates`), open menus and the full-size poster overlay. |
| `ResultPageLogic` | Pure rules, unit-tested in `ResultPageLogicTest`: tabs and panes, stats cells, download menu / button, chapter filter, search and sort, chips, Translate button, language label, translated metadata display. |
| `translator/MetadataLabels` | Translation of the latest chapter name and the tags (`MetadataLabelsTest`). |

### State (`ResultState`)

Response (`ImmutableSearchResponse` with download state), loading / error, bookmark, open dialog,
reviews, `isImported`, translator `novelId`, `sourceLang` (zh / ja / ko, or null), the imported
book's `languageLabel`, `translationEnabled`, all chapters and the listed (filtered / searched /
sorted) chapters, the filter and sort (stored in the same preferences as the XML page:
`RESULT_CHAPTER_FILTER_*`, `RESULT_CHAPTER_SORT`), read times per chapter
(`EPUB_CURRENT_POSITION_READ_AT`), chapter statuses, the selected tab, translated title, synopsis
and labels, Show original, tag → provider-listing index, and the Translate button state.

### Actions and effects

Actions cover bookmark, tabs, Show original, retry, filter / sort / search, download (download,
pause, resume, stop, partial download with a chapter dialog, delete with confirmation), read epub,
stream read, chapter tap (reader at that chapter) and long press (mark read / unread, translate
this chapter, download from this chapter), Translate, Analyze novel, Glossary, Source language,
tag chips, translation progress and resume.

Effects the fragment handles:

- `OpenTranslateDialog` → `TranslateRangeDialogFragment` with the page's chapter list (translate
  chapter / range / whole novel, analysis hint, overnight batch). A chapter's "Translate this
  chapter…" prefills a one-chapter range (`startChapter`).
- `OpenAnalyze` → `NovelChapterSource.remember` + `AnalyzeNovelDialogFragment`.
- `OpenGlossary` → `GlossaryFragment`; `OpenTag` → the source's listing for that genre.
- `Toast` / `Error`.

Opening a page does what the XML page did: last-access time, bookmark data refresh for bookmarked
novels, `BookDownloader2.openChanged` (clears the library's "+N new", see
[library-updates.md](library-updates.md)), the stored download state, history on read.

## Layout

Breakpoint from the window width the screen gets (`BoxWithConstraints`), not the orientation
([`AdaptiveLayout`](../app/src/main/java/com/lagradost/quicknovel/util/AdaptiveLayout.kt)):

- **Below 840 dp** (phones, the Fold cover screen, medium windows): one column. Tab row Novel /
  Reviews (if the source has reviews) / Related (if any) / Chapters · N; a lone Novel tab hides the
  row. Items are capped at `MaxContentWidth` (840 dp) and centred.
- **840 dp and up** (Fold inner screen at ~930 dp, tablets): two panes. Details on the left
  (42 % of the width, between 360 and 520 dp) with its own Novel / Reviews / Related tabs, the
  chapter list on the right; each scrolls on its own.

Fold / unfold resizes the activity in place (no recreation). The list states are created above
the layout switch, so the details, chapter, review and related positions survive it. The selected
tab is in the view-model state: Chapters selected on the cover screen shows Novel in the two-pane
layout (the chapters are on the right) and comes back when folded again.

Details pane, top to bottom: blurred poster header (poster, tap for full size; translated title
with the original underneath; author; status; latest chapter), Bookmark / Open in browser / Share
(the last two not for imported books), stats (views, rating with votes, chapters; cells without
data are left out), the imported book's source language (tap to choose), the slow-source warning,
Read epub / download button (tap or long press for its menu) / Stream read and the progress bar,
Translate / Glossary / ⋮ (Analyze novel…, Glossary, Source language… for imports), Show original
toggle and synopsis (tap to expand), tags (clickable when the provider lists them as a genre).

Chapter pane: count, search, filter and sort; rows with name, date, read rows dimmed, and a
Translated / Source changed / Queued chip ([reader.md](reader.md#chapter-list-status-chips)) from
`ChapterStatusSource`, refreshed every 4 s while the page is on screen.

## Translated metadata

For zh / ja / ko novels (and imported books with a known language), when titles and synopses are
translated at all (`MetadataTranslations.anyEnabled`, see [browse.md](browse.md#translated-titles-and-synopses)):

- title + synopsis: `MetadataTranslations.novel` (one call, cached), as before;
- latest chapter name + tags: `MetadataLabels` sends the page's labels (only CJK text that is not
  mostly Latin already, at most 30) through the batched title path, so they share one small call
  per page and are cached in `novel_metadata_translations` under `label:<lang>/<text>` ids;
- "Show original" / "Show translation" switches all of them (and the chapter names);
- chapter names in the list are translated with the free Google endpoint when "Translate titles
  with Google (free)" is on, as on the XML page (kept in memory for the session).

## What the XML page is still for

`ResultFragment` / `fragment_result.xml` / `ResultViewModel` / `ChapterAdapter` are no longer
navigated to; they stay compiling until a later cleanup. `ResultViewModel` is still used by the
preview bottom sheet in `MainActivity` (long press on a poster), and its companion holds the chapter
filter / sort preferences that both pages share. `MetadataTranslationViews.bindPreview` is still
the preview sheet's binding; `bindResult` belongs to the old page only.

## Limitations

- The language row and the ⋮ menu are the only places to change an import's language; online
  novels take the provider's language.
- Chapter-name translation is per visible row (Google), not batched, as before.
- The XML page's FABs (scroll reviews to top, chapter filter) are replaced by the chapter pane's
  filter button; reviews have no scroll-to-top button.
