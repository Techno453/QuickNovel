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
| `ui/reader/paged/PagedReaderController`, `PageTextView`, `PageSelection` | Paged mode (see below): pages, link taps, selection |
| `ui/reader/ReadingAnchor` | Which line is saved as the position, which paragraph a saved char is in |
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
both modes read and write the same keys. The rules are pure and unit tested
(`ReadingAnchor`, `PageBreaker.pageFor`, `PagePositionTest`):

- Scroll mode saves the first character of the first line drawn entirely inside the text area
  (top and bottom; `ReadingAnchor.pick`). Only when no line fits (one line or image taller than
  the screen) does it fall back to the first line still partly on screen; it never saves a line
  that has scrolled off the top.
- Restoring a position (start-up, TTS) maps the char to the paragraph that contains it
  (`ReadingAnchor.paragraphFor`); it used to pick the paragraph after it, so a position in the
  middle of a paragraph reopened at the next one. A position inside the first paragraph seeks to
  its line instead of the chapter start, and the seek takes the line whose (exclusive) end is past
  the char, not the line before it. On a cold start the list is not laid out when the seek first
  runs, so the line seek retries for up to 30 frames, and the position is recorded only after it.
- Pages: see "Position" under Paged mode.

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
- **Links.** A tap on a link calls the link span's own `onClick` (the Markwon link resolver,
  the same handler `LinkMovementMethod` uses in scroll mode) and wins over the page-turn zones.
  The hit test uses the character drawn under the finger, not the nearest caret position. Links
  are drawn in the paragraph view's link colour (pages used to draw them with a transparent
  link colour, so link text was invisible).
- **Position.** Turning a page saves the position of the page's first character. A title page
  saves char `-1`; scroll mode treats that as the chapter start. Opening or switching to pages
  shows the page whose text contains the saved character (`PageBreaker.pageFor`: the last page
  of the chapter starting at or before it). Because scroll mode saves the first fully visible
  line, the page shown holds the line the reader was at the top of, not the line cut off above
  it. Scroll and page widths can differ, so that line may start inside a page line; the page
  holding that character is still the one shown.
- **Re-pagination.** Fold/unfold, rotation, split screen, margins, max width, font, size,
  spacing and bionic reading all rebuild the pages. The saved position (not the page number)
  picks the page afterwards, so going back and forth between sizes does not drift.
- **TTS.** Works in paged mode. The line being read is highlighted (rounded path from
  `Layout.getSelectionPath`), and the pager turns to the page holding it. If the reader moves
  past the loaded chapters, the window follows.
- **Selection.** Long-press selects the word under the finger (`GlossaryLookup.wordAt`: a
  Latin word, a katakana or hangul run, or one hanzi/kanji) and starts a selection mode:
  - the selection is drawn in the platform selection colour with the platform's left and right
    handles (`android:textSelectHandleLeft/Right`); dragging a handle extends or shrinks it, the
    handles swap when they cross, and at least one character stays selected
    (`PageSelection.drag`, unit tested). While a handle is dragged the pager does not swipe.
  - A selection stays inside one paragraph and the part of it on the current page.
  - A floating toolbar (`ActionMode.TYPE_FLOATING`) offers **Copy** (clipboard; a toast below
    Android 13, which shows its own confirmation) and **Glossary** for novels with a glossary
    (zh/ja/ko sources and imported books opened in the app's reader). Glossary on the untouched
    long-pressed word runs the same lookup as a scroll-mode long-press (longest matching term);
    on an extended selection it runs the selection lookup.
  - A tap anywhere, a page turn, re-pagination or Back ends the selection mode.
- **Not supported on pages.** The scroll types (infinite / buttons / overscroll) only apply to
  scroll mode. Selections do not cross paragraphs or pages.

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

  On wide windows the sheet is capped in width. The title has the glossary icon; Edit, Lock and
  Add to glossary are outlined sentence-case buttons (`TranslateOutlinedButton`, as in the
  Translate and Analyze dialogs) with the icon next to the text; Approve / Dismiss are sentence
  case.
- **No match.** The sheet says the word is not in the glossary and offers **Add to glossary**,
  prefilled with the word (possessive stripped) and the current chapter.

## AI translation in the reader

For books that translate chapters when opened (zh / ja / ko sources, and imported books with a
known language; `AbstractBook.translatesOnDemand`):

- **Translate tab** of the reader settings: an "AI translation" section at the top with the
  **Translate chapters when opened** switch. It is the same preference as Settings → AI
  translation and the setup wizard (`SecurePrefs` on-demand flag); changing it reloads the loaded
  chapters that show the original because of it. Upstream's ML Kit translation stays underneath
  as "On-device translation (ML Kit)". Other books show only the ML Kit section.
- **Original notice:** when such a chapter is shown as the original because the switch is off or
  there is no API key, one small line under the chapter title says why ("Showing the original.
  Turn on Translate chapters when opened to translate it." / "No API key. Add one in Settings →
  AI translation."). In scroll mode it is part of the title row, in paged mode part of the title
  page. Never after Reload / Show original, and not when a translation failed. The rule is
  `ui/reader/OriginalNotice` (`OriginalNoticeTest`); `ChapterStartSpanned.notice` carries it.
- **Show original:** long-press on a translated chapter's title offers "Show original" (on other
  chapters "Reload Chapter"); both reload the original text.
- **Preview** (result page; `QuickBook.isPreview`): only the open chapter is translated, without
  the glossary; preloaded chapters wait as the original until opened. The title line says
  "Preview · translated without the glossary" (or "Preview · …" with the original notice's
  reason, or "Preview · translated when you reach this chapter"), the Translate tab adds a preview
  note, and the glossary lookup is off. Details:
  [translation-pipeline.md](translation-pipeline.md#preview).

## Imported books

Imported EPUB and TXT books open as `RegularBook` with their translator id (`Imported/...`, passed
as the `importedNovelId` intent extra). Chapters are split by `EpubChapters`, the same plan the
translation pipeline uses, so chapter *i* matches the result page and the DB. When AI translation is
enabled for the reader and the book's language is known (detected or set on the result page), chapters are translated
on demand through `ChapterTranslationRepository.getOrTranslate` like online chapters, in scroll and
paged mode, with glossary long-press. Long-press on the chapter title → Show original shows the
original (see [AI translation in the reader](#ai-translation-in-the-reader)).
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
