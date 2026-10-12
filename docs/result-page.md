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

Chapter pane: count, search, filter and sort; rows with name (translated, with the original and
the date on a small second line), read rows dimmed, and a Translated / Source changed / Queued
chip ([reader.md](reader.md#chapter-list-status-chips)) from `ChapterStatusSource`, refreshed
every 4 s while the page is on screen. The search matches the original and the translated names.

Reviews tab: the list loads more pages as it nears the end; a small "To top" button fades in once
three reviews are scrolled past (`ResultPageLogic.showScrollToTop`) and scrolls back to the top.

## Translated metadata

For zh / ja / ko novels (and imported books with a known language), when titles and synopses are
translated at all (`MetadataTranslations.anyEnabled`, see [browse.md](browse.md#translated-titles-and-synopses)):

- title + synopsis: `MetadataTranslations.novel` (one call, cached), as before;
- latest chapter name + tags: `MetadataLabels` sends the page's labels (only CJK text that is not
  mostly Latin already, at most 30) through the batched title path, so they share one small call
  per page and are cached in `novel_metadata_translations` under `label:<lang>/<text>` ids;
- chapter names: see below;
- "Show original" / "Show translation" switches all of them, chapter names included (the toggle
  is also offered when only chapter names are translated).

### Chapter names

Translated through the same batched, cached title path, only for what is on screen
(`translator/ChapterNameWindow.kt`, `ChapterNameWindowTest`):

- The chapter list reports its first and last visible rows (`ResultPageAction.ChapterRowsVisible`,
  rows of the listed, i.e. filtered / sorted, chapters). Nothing is requested while it scrolls;
  once it has been still for 300 ms, `ChapterNameWindow` plans the visible rows, then 15 below and
  5 above, skipping names already requested or translated and text that is not CJK. A batch is
  at most 40 names; `MetadataTranslator` sends them as AI calls of 20 (one or two calls per
  stop), or through the free Google path, four requests at a time.
- `ChapterNameTranslator` keeps one batch in flight: windows passed while it runs are never sent,
  and the window the list is on when it ends goes next. A fling through 1,400 chapters costs only
  the window it stops on. Translations already in memory show at once, without the debounce.
- Cache ids are `MetadataLabels.labelId` (`label:<lang>/<name>`), so a chapter name and the
  "Latest:" label share one row in `novel_metadata_translations`; rows are kept forever.
- Same switch and engines as titles: when `MetadataTranslations.anyEnabled` (AI with a key and
  "Translate titles and synopses with AI" on, else the "Auto-translate titles & synopses" Google
  option). The original stays visible until the translation arrives, then moves to the small
  second line.
- The reader's chapter picker (`ReadActivity2.showChapterDialog`) uses the same driver on its
  `ListView`: it opens at once with the originals and translates the visible rows as you scroll,
  each translated row showing the original underneath (it used to Google-translate every name,
  one request each, before opening).

## The XML page (removed)

`ResultFragment`, `ChapterAdapter`, `ReviewAdapter` and their resources were deleted once nothing
opened them: layouts `fragment_result`, `simple_chapter`, `result_tag`, `result_review`,
`result_review_tag`, `chapter_filter_popup`, `chapter_dialog`, `loading_downloads`,
`loading_review`, `loading_line_short2`; drawables `chapter_status_chip`, `outline_big_25_gray`,
`icon_boxnovel`; the `chapter_status_chip_space` dimen; the strings `synopsis`, `bookmarks`,
`download_text`, `novel_imagecover_descript`, `background_image_descript`,
`quickstream_novel_text`, `start_chapter` (all languages); `MetadataTranslationViews.bindResult`.
`chapter_select_dialog` stays (reader). `ResultViewModel` stays: the preview bottom sheet in
`MainActivity` (long press on a poster) uses it, and its companion holds the chapter filter /
sort preferences the Compose page reads; `MetadataTranslationViews.bindPreview` is the preview
sheet's binding. The code in `ResultViewModel` that only the old page called (chapter list,
reviews, download actions) is still there.

## Limitations

- The language row and the ⋮ menu are the only places to change an import's language; online
  novels take the provider's language.
- Chapter-name translation uses the novel-title prompt (`MetadataPrompts.titlesSystemPrompt`);
  results read fine for chapter names but are not tuned for them.
- Without an API key the Google path does one request per name (four at a time): a new window
  takes several seconds. The chapter search only matches translated names that were already
  translated (seen rows). Names that failed with every engine are not retried in the same
  session (as for titles); on the emulator Google started answering with its HTML block page after
  the reader had fallen back to Google for whole chapters, and new windows stayed in Chinese.
- The XML page's chapter filter FAB is replaced by the chapter pane's filter button.
