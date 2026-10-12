# Reader

## Structure

`ReadActivity2` only wires the collaborators together. `ReadActivityViewModel` owns the book,
the chapter window and TTS. The plain preferences live in `ReaderPreferenceStore`, which the
view model delegates to.

| Class | Responsibility |
|---|---|
| `ui/reader/ReaderChromeController` | System bars, cutout, toolbar and bottom bar animations, clock/battery overlay, orientation, keep-screen-on |
| `ui/reader/ReaderLayoutController` | Text column: user margins, the max-width cap on wide windows, density changes (cover/inner screen), tabletop posture |
| `ui/reader/ReaderScrollController` | Scroll mode: the paragraph RecyclerView (`TextAdapter`), position from the visible lines, seeking, TTS line lock, overscroll chapter change, volume paging |
| `ui/reader/paged/PagedReaderController` | Paged mode (see below) |
| `ui/reader/ReaderTtsController` | TTS buttons, status, sleep timer, media actions; forwards the line being read to the active display |
| `ui/reader/ReaderSettingsSheet` | The settings bottom sheet |
| `ui/reader/ReaderGlossaryController`, `GlossaryLookup`, `GlossaryLookupSheet` | Long-press / selection glossary lookup |
| `ReaderBooks.kt` | `AbstractBook`, `QuickBook` (streamed / downloaded), `RegularBook` (epub) |
| `ReaderPreferences.kt`, `ReaderPreferenceDelegates.kt` | Reader preferences and their LiveData |
| `MlChapterTranslator` | ML Kit on-device chapter translation with a file cache |

The view model publishes a window of chapters (`chapter: LiveData<ChapterUpdate>`): the current
chapter, one before it and two or more after it. Each chapter is a list of `SpanDisplay` items:
`ChapterStartSpanned`, then `TextSpan` paragraphs, or `LoadingSpanned` / `FailedSpanned`. The
activity sends each update to whichever display is active.

**Position.** A position is a `ScrollIndex(index = chapter, innerIndex = paragraph,
char = character offset in the chapter)`. It is saved under `reader_epub_position*` keys, and
both modes read and write the same keys.

## Paged mode

Settings → Settings tab → **Reading mode: Scroll / Pages**. The key is `reader_reading_mode`:
`0` is scroll (the default) and `1` is pages. In pages mode the scroll types (infinite, buttons,
overscroll) are disabled and the continuous chapter window is used.

- **Pagination.** `ReaderPaginator` lays out each paragraph with `StaticLayout`. It takes the
  paint, line spacing, break strategy and paragraph padding from a reader paragraph view that has
  the current `TextConfig` applied, so pages wrap exactly like the scroll view. Then
  `PageBreaker` (pure, unit tested) cuts the lines into pages:
  - Lines are never split.
  - Paragraph spacing is dropped at the top of a page.
  - A line taller than a page gets a page of its own.
  - Separators move whole to the next page.

  The text area is the pager size minus the column insets (the same horizontal padding and
  max-width cap as scroll mode, the user's top margin plus 16dp, and 28dp for the page number).
  Each chapter starts with a title page. Images get a page of their own, and tapping one opens it
  full screen. Loading and failed chapters show a status page; tapping a failed one retries.
  Pagination runs off the main thread, and each chapter's pages are cached until its paragraphs,
  the text area or the text settings change.
- **Navigation.** ViewPager2 shows the pages. Tap the left third for the previous page, tap the
  right third or swipe for the next. The middle third toggles the bars. Volume keys turn pages
  when "scroll with volume keys" is on. Pages flow across chapter boundaries, and the window
  moves along as you read.
- **Position.** Turning a page saves the position of the page's first character. A title page
  saves char `-1`; scroll mode treats that as the chapter start. Opening or switching to pages
  shows the page that contains the saved character, so switching modes keeps the place.
- **Re-pagination.** Fold/unfold, rotation, split screen, margins, max width, font, size,
  spacing and bionic reading all rebuild the pages. The saved position (not the page number)
  picks the page afterwards, so going back and forth between sizes does not drift.
- **TTS.** Works in paged mode. The line being read is highlighted (rounded path from
  `Layout.getSelectionPath`), and the pager turns to the page holding it. If the reader moves
  past the loaded chapters, the window follows.
- **Glossary.** Long-press a word on a page to run the same lookup as in scroll mode. Text
  selection is not available on pages.
- **Not supported on pages.** Links in text are not clickable, and the scroll types
  (infinite / buttons / overscroll) only apply to scroll mode.

## Glossary lookup

This applies to novels from translated (Chinese, Japanese, Korean) sources that have a Story
Bible. Nothing changes in the reading view and terms are not highlighted.

- **Long-press** a word (when selectable text is off). With selectable text on, the selection
  menu has a **Glossary** item instead.
- **Matching** (`GlossaryLookup`, pure, unit tested). The word under the finger is extended to
  the longest glossary term that covers it:
  - English names: whole words, ignoring case.
  - Sources and aliases: exact match, which covers untranslated CJK text.
  - A possessive `'s` / `’s` is ignored.
  - Inner apostrophes such as O'Brien are kept.
  - Katakana and hangul runs count as one word.

  A single word of a multi-word character name gives a weaker match, which the sheet shows as
  "Matched one word of the name".
- **The sheet** shows source, English, gender (characters, or when known), category, aliases,
  notes, first chapter and status (locked / auto-added). It also offers:
  - Edit, which opens the term editor.
  - Lock / Unlock.
  - Approve / Dismiss for pending suggestions about this term: same source, an alias, or
    "same English as existing …".

  On wide windows the sheet is capped in width.
- **No match.** The sheet says the word is not in the Story Bible and offers **Add to glossary**,
  prefilled with the word (possessive stripped) and the current chapter.

## Imported books

Imported EPUB and TXT books open as `RegularBook` with their translator id (`Imported/...`, passed
as the `importedNovelId` intent extra). Chapters are split by `EpubChapters`, the same plan the
translation pipeline uses, so chapter *i* matches the result page and the DB. When AI translation is
enabled for the reader and the book's language is known (detected or set on the result page), chapters are translated
on demand through `ChapterTranslationRepository.getOrTranslate` like online chapters, in scroll and
paged mode, with glossary long-press. Long-press on the chapter title → Reload shows the original.
Details: [translation-pipeline.md](translation-pipeline.md#imported-books-epub--txt).

## Chapter list status chips

On the result page, chapters of translated novels (online zh/ja/ko sources and imported books
with a known language) show a chip:

- **Translated**: a stored translation exists.
- **Source changed**: the stored translation is stale (`translated_chapters.stale`, set by
  `ChapterTranslationRepository` when the `SourceHash` check says CHANGED).
- **Queued**: the read-ahead queue holds the chapter, or it is in an overnight batch that has
  not finished (`BatchManager.chaptersInUnfinishedBatches`).

Queued wins over stale, stale over translated. `ChapterStatusSource` / `TranslatorChapterStatusSource`
provide the statuses; the lookups sit behind `ChapterStatusQueries` (translator DAO, batch jobs,
read-ahead queue) so the merging is unit tested with fakes (`ChapterStatusSourceTest`). A failing
lookup only drops its own chips. The adapter refreshes them every 4 s while the list is visible and
updates bound rows in place. A batch chapter stays "Queued" until its batch job has finished
processing, even if it was stored a moment earlier.

## CJK text

The reader does not bundle CJK fonts. The default reader font (serif) and fonts picked from
`/system/fonts` fall back to the system Noto CJK fonts. Untranslated Japanese chapters were
checked in both modes with the default font, ComingSoon and DroidSans, and showed no missing
glyphs.
