# UI cohesion: the translation experience

Audit of every surface that touches AI translation, the vocabulary they now share, what was
changed in pass one (settings, setup wizard, Translate / Analyze dialogs, Spending, glossary,
notifications, shared strings, icons and styles) and the change list for pass two in the areas
pass one could not edit (`ui/result/`, `ui/reader/`, `ui/browse/`, the reader core in the root
package, `translator/`). Pass two is done; see [Part 2](#part-2-done-in-pass-two).

Screenshots are from the `Fold` AVD (emulator-5556) with size overrides: phone 1080x2400 @420,
Fold 8 cover 1248x1972 @420, Fold 8 inner 2448x1848 @400. They live in
[`ui-cohesion/`](ui-cohesion/) as `before-*.jpg` (master cfb28bb) and `after-*.jpg` (this pass).
No API key was set on the device, so nothing was translated with a model; the translated titles
come from the free Google option, switched on for the Browse shots only and off again.

## Vocabulary

One word per concept, sentence case, in `res/values/strings_translate.xml` (shared by settings,
the wizard, the dialogs, Spending, the glossary and the notifications).

| Concept | Use | Do not use | Why |
|---|---|---|---|
| **Translate** | Button and action (result page, dialog title "Translate chapters", dialog button) | Start, Translate Chapters | The button that opens the dialog already says Translate; the dialog's confirm button now says the same. |
| **AI translation** | The feature and its settings group ("Settings → AI translation") | AI Translation, Translation, Translator | Matches the group title; upstream group titles are sentence case ("App settings"). |
| **Original** | "Show original" / "Show translation"; "shows the original" | raw, source text (in UI copy) | Already the result page / Browse wording. |
| **Glossary** | The per-novel list of names and terms, its screen, the reader lookup, the Translate dialog section, analysis results ("Glossary updated: 5 new terms"), export/import | Story Bible, character sheet | See below. |
| **Analyze novel** | The pre-pass action everywhere (glossary menu, result ⋮, Translate dialog button, setting names) | Analyze first chapters…, build the character sheet | One name for one action. |
| **Read ahead** | Setting, wizard toggle, notification title | Auto-translate ahead, Translate ahead while reading, Translating ahead | Short, and the same in all three places. |
| **Translate chapters when opened** | The on-demand switch | Translate chapters on demand | Says when it happens. |
| **Overnight batch** | The OpenAI Batch API option and its notifications; "Overnight (only while charging, normal price)" for other providers | Overnight translation, batch (alone) | Distinguishes the half-price batch from the charging-only job. |
| **Translation job** | A range translation started from the Translate dialog | range job, long job | Users never see "range". |
| **Background translation** | Translation jobs plus read ahead (charging / battery settings) | long jobs | Says what is affected. |
| **Spending** | The screen; "spend cap" for limits ("Monthly spend cap", "Per-job spend cap") | Spend (alone), Per-job cap | The screen title was already Spending. |
| **Source language** | Language of a novel | language of this book | Already the result page label. |
| **Translation model / Fallback model / Analysis model** | The three model settings | LLM | "LLM" appeared once in a summary. |
| **API key**, **Test connection** | Key rows and the free key check (wizard and settings) | Verify, "API key is valid!" | The wizard already said Test connection. |
| **Source changed** | Stale translations (chip, Translate dialog checkbox) | stale | The chip's wording. |

**Glossary vs Story Bible vs character sheet.** The three names described one thing. "Story
Bible" is the better technical name (it covers places, sects, techniques, the premise and the
style, not only characters) and stays in code, prompts, docs and the JSON export format. In the
UI the only word is **Glossary**: every entry point (result page button, reader action, Translate
dialog) already said Glossary, a second noun for the same screen was the main source of
confusion, and "character sheet" was wrong for non-character terms. The premise and name style
live under the glossary's "Translation style…".

## Inventory

| # | Surface | Code | Before | After |
|---|---|---|---|---|
| 1 | Setup wizard (5 steps) | `ui/setup/SetupWizardScreen.kt` | [1](ui-cohesion/before-wizard-1.jpg) [2](ui-cohesion/before-wizard-2.jpg) [3](ui-cohesion/before-wizard-3.jpg) [4](ui-cohesion/before-wizard-4.jpg) [5](ui-cohesion/before-wizard-5.jpg) | [1](ui-cohesion/after-wizard-1.jpg) [2](ui-cohesion/after-wizard-2.jpg) [3](ui-cohesion/after-wizard-3.jpg) [4](ui-cohesion/after-wizard-4.jpg) [5](ui-cohesion/after-wizard-5.jpg), [cover](ui-cohesion/after-wizard-5-cover.jpg), [inner](ui-cohesion/after-wizard-5-inner.jpg) |
| 2 | Settings → AI translation | `ui/settings/TranslationSettings.kt`, `SettingScreen.kt`, `SettingsFragment.kt` | [1](ui-cohesion/before-settings-ai-1.jpg) [2](ui-cohesion/before-settings-ai-2.jpg) [3](ui-cohesion/before-settings-ai-3.jpg) [4](ui-cohesion/before-settings-ai-4.jpg) [5](ui-cohesion/before-settings-ai-5.jpg) [6](ui-cohesion/before-settings-ai-6.jpg) [7](ui-cohesion/before-settings-ai-7.jpg), [cap dialog](ui-cohesion/before-settings-cap-dialog.jpg), [cover](ui-cohesion/before-settings-cover.jpg), [inner](ui-cohesion/before-settings-inner.jpg) | [1](ui-cohesion/after-settings-ai-1.jpg) [2](ui-cohesion/after-settings-ai-2.jpg) [3](ui-cohesion/after-settings-ai-3.jpg) [4](ui-cohesion/after-settings-ai-4.jpg) [5](ui-cohesion/after-settings-ai-5.jpg) [6](ui-cohesion/after-settings-ai-6.jpg), [cover](ui-cohesion/after-settings-cover.jpg), [inner](ui-cohesion/after-settings-inner.jpg), [light](ui-cohesion/after-settings-light.jpg) |
| 3 | Spending | `ui/translate/SpendScreen.kt` | [phone](ui-cohesion/before-spend.jpg) | [phone](ui-cohesion/after-spend.jpg) |
| 4 | Translate chapters dialog | `ui/translate/TranslateRangeDialogFragment.kt`, `layout/dialog_translate_range.xml` | [phone](ui-cohesion/before-translate-dialog.jpg), [cover](ui-cohesion/before-translate-dialog-cover.jpg), [inner](ui-cohesion/before-translate-dialog-inner.jpg) | [phone](ui-cohesion/after-translate-dialog.jpg), [cover](ui-cohesion/after-translate-dialog-cover.jpg), [inner](ui-cohesion/after-translate-dialog-inner.jpg), [light](ui-cohesion/after-translate-dialog-light.jpg), [black](ui-cohesion/after-translate-dialog-black.jpg) |
| 4b | Analyze novel dialog | `ui/glossary/AnalyzeNovelDialogFragment.kt` | [phone](ui-cohesion/before-analyze-dialog.jpg) | [phone](ui-cohesion/after-analyze-dialog.jpg) |
| 5 | Notifications (job, read ahead, overnight batch, library updates) | `ui/translate/TranslationForegroundService.kt`, `BatchPollWorker.kt`, `updates/LibraryUpdateNotifier.kt` | code review (no key on the device) | code review |
| 6 | Glossary screen and dialogs | `ui/glossary/*`, `layout/fragment_glossary.xml` | [list](ui-cohesion/before-glossary.jpg), [add term](ui-cohesion/before-glossary-add-term.jpg) | [list](ui-cohesion/after-glossary.jpg), [menu](ui-cohesion/after-glossary-menu.jpg), [style](ui-cohesion/after-glossary-style.jpg), [add term](ui-cohesion/after-glossary-add-term.jpg), [light](ui-cohesion/after-glossary-light.jpg), [black](ui-cohesion/after-glossary-black.jpg) |
| 6b | Reader glossary lookup sheet | `ui/reader/GlossaryLookupSheet`, `layout/read_glossary_lookup.xml` | — | [phone](ui-cohesion/after-reader-glossary-lookup.jpg) (new copy, old style) |
| 7 | Result page (Compose) | `ui/result/ResultScreen.kt` | [novel](ui-cohesion/before-result-novel.jpg), [⋮ menu](ui-cohesion/before-result-overflow.jpg), [chapters](ui-cohesion/before-result-chapters.jpg), [chapter menu](ui-cohesion/before-result-chapter-menu.jpg) | [translated title, Show original](ui-cohesion/after-result-translated.jpg) |
| 8 | Reader | `ReadActivity2`, `ui/reader/ReaderSettingsSheet.kt`, `ReaderBooks.kt` | [chapter](ui-cohesion/before-reader.jpg), [settings sheet](ui-cohesion/before-reader-settings.jpg), [Translate tab](ui-cohesion/before-reader-translate-tab.jpg) | [chapter](ui-cohesion/after-reader.jpg) |
| 9 | Browse | `ui/browse/*`, `ui/common/TranslatedTitle.kt`, `MetadataTranslationViews.kt` | [home](ui-cohesion/before-browse.jpg) | [translated rails](ui-cohesion/after-browse-translated.jpg), [All sources](ui-cohesion/after-browse-all-sources.jpg), [preview sheet](ui-cohesion/after-browse-preview.jpg) |
| 10 | Error and status copy | strings, `ConnectionTest`, `SpendCaps`, service | see [Copy](#copy) | |

## Findings

### Terminology

Deviations found (all fixed in pass one unless marked **P2**, which pass two fixed):

| Text | Where | Now |
|---|---|---|
| "Story Bible: 0 characters, 0 terms" | Translate dialog `bible_status` | section "Glossary", "0 characters, 0 other terms" |
| "Story Bible updated: N new terms" | `analysis_done*` (glossary toast, notification) | "Glossary updated: …" |
| "… is not in this novel's Story Bible." | reader lookup `reader_glossary_not_found` | "… this novel's glossary." |
| "Export Story Bible JSON", "Story Bible exported", "Import Story Bible" | glossary export / import | "Export glossary (JSON)", "Glossary exported", "Import glossary" |
| "builds the character sheet" | `analyze_message`, Chapters to analyze, Analyze before first translation, Analysis model dialog, wizard welcome | "builds the glossary" |
| "Analyze first chapters…" | Translate dialog button | "Analyze novel…" |
| "Auto-translate ahead" / "Translate ahead while reading" / "Translating ahead" | settings / wizard / notification | "Read ahead" |
| "Translate chapters on demand" … "with the LLM" | settings | "Translate chapters when opened" … "with the AI model" |
| "Long jobs", "Range translations" | battery settings | "Background translation", "Translation jobs" |
| "Per-job cap" | settings, Spending | "Per-job spend cap" |
| "Verify" → "API key is valid!" / "The provider rejected this key (…)" | settings key dialog | "Test connection" with the wizard's messages |
| "Set your API key in Settings → Translation first", "No API key set. Go to Settings → Translation." | dialogs, service | "No API key. Add one in Settings → AI translation." |
| "Settings → AI Translation" | wizard, Translate dialog hint | "Settings → AI translation" |
| "You can switch at any time, also per novel." | wizard model page (there is no per-novel model) | "You can change it any time in Settings → AI translation." |
| "Est. cost: … — gpt-6-luna" (raw id) | Analyze dialog | "Estimated cost: …, GPT-6 Luna" (label, as in the Translate dialog) |
| "Overnight (batch, ~50% off, …)", "Overnight (runs only while charging)" | Translate dialog | "Overnight batch (about 50% off, …)", "Overnight (only while charging, normal price)" |
| "Re-translate N stale chapter(s) in this range (source changed)" | Translate dialog | "Also re-translate N chapters whose source changed" |
| "Overnight translation queued / ready / failed" | batch notifications | "Overnight batch …" |
| "Translation Progress" | notification channel | "AI translation" |
| "Auto-translate titles & synopses" | `docs/result-page.md`, `docs/browse.md` | "Translate titles with Google (free)" (docs updated) |
| "Google translate" + "Online translation" (ML Kit) on the reader tab named "Translate" | `ReaderSettingsSheet`, `read_bottom_settings.xml` | **P2**: "AI translation" section on top, ML Kit section "On-device translation (ML Kit)" (R4) |
| "Translation error: …" | `ResultFragment2` toast (hard-coded) | **P2**: "Translation failed: …" (R3) |
| "raise the cap in Settings → AI Translation" | `translator/SpendCaps.kt` | **P2**: "AI translation" (T1) |
| "over the per-job cap" | `translator/SpendCaps.kt` | **P2**: "per-job spend cap" (sweep) |
| SpendKind label "Read-ahead" | `translator/Spend.kt` (Spending, latest calls) | **P2**: "Read ahead" (T2) |
| "Analysis failed: No API key" | `translator/NovelAnalyzer.kt` | **P2**: the `translator_no_api_key` text (T4) |
| "Expected … a Story Bible export" (glossary import error toast) | `translator/StoryBibleJson.kt` | **P2**: "a glossary export" (sweep) |

### Placement

- **Dead end, fixed:** the glossary's menu (Analyze novel, Translation style, Review auto-added,
  Load presets, Export, Import) was registered on the activity, which has no action bar, so it
  never appeared; the empty state told users to "load presets from the menu". The glossary now has
  its own toolbar (back, title, novel name, ⋮). Translation style… was reachable from nowhere
  before.
- **No-op button, fixed:** "Review new terms" was always shown and opened a dialog that closed
  itself when nothing was pending. It is hidden at 0 and says "Review N new terms".
- **Hard to find, fixed:** the 40 AI translation rows were one flat list (with two sub-headers
  styled exactly like group headers) ending in six name-style rows. Now seven sections:
  Provider and key · Reading and background jobs · Glossary and analysis · Spending and limits ·
  Titles, synopses and search · Default translation style · Advanced (fallback model and key,
  strict mode, story context).
- **First-run gap, fixed:** "Translate chapters when opened" is off by default and the wizard never
  showed it, so after setup the reader silently showed the original (and the wizard's Read ahead
  toggle had nothing to follow). The Done page now has the same switch.
- **Duplicates (P2, fixed):** the result page ⋮ menu repeated "Glossary" next to the Glossary
  button (R1).
  "Analyze novel…" is in three places (glossary ⋮, result ⋮, Translate dialog); that is fine, the
  name is the same everywhere now.
- **Silent fallback (P2, fixed):** with no key, or with "Translate chapters when opened" off, the
  reader showed the original without saying why (`ChapterTranslationRepository.getOrTranslate`
  returns null). A notice line under the chapter title says why now (R5).
- **Two translations in the reader (P2, fixed):** the reader settings sheet's "Translate" tab was
  upstream's ML Kit "Google translate" (defaulting to English → English for a Korean novel) with
  no AI translation control. It now starts with the AI translation switch (R4).
- Spending is reachable from settings and the wizard; the Translate dialog shows "Spent this
  month" but does not link to it (acceptable; optional later).

### Visual

- **Icons, fixed:** every one of the ~40 AI translation rows used the translate icon. Each concept
  now has one Material Symbols icon (`res/drawable`): translate `ic_baseline_translate_24` (now the
  same glyph as `translate_24px`), glossary `ic_glossary_24`, original `ic_original_24`, spending
  `ic_spend_24`, API key `ic_api_key_24`, model `ic_model_24`, read ahead `ic_read_ahead_24`,
  analysis `ic_analyze_24`, battery `ic_battery_charging_24`, overnight `ic_overnight_24`, titles
  `ic_titles_24`, search `ic_search_translate_24`, name style `ic_name_style_24`, fallback
  `ic_fallback_24`, strict `ic_strict_24`, story context `ic_story_context_24`, setup
  `ic_setup_24`. Notifications used the download arrow (`netflix_download`); they use the
  translate icon now. The result page's Glossary button used `ic_baseline_menu_book_24`
  (the reader / library icon) — R1, now `ic_glossary_24`.
- **Buttons, fixed in the XML dialogs:** MaterialComponents buttons were ALL CAPS with wide letter
  spacing ("ANALYZE FIRST CHAPTERS…") next to sentence-case Compose buttons. `styles_translate.xml`
  adds `TranslateOutlinedButton` / `TranslateTextButton` (sentence case, no letter spacing).
  Remaining: the reader lookup sheet's filled white "Add to glossary" button (R6, outlined since pass two).
- **Dialog layout, fixed:** content padding was 16 dp while the platform dialog title sits at
  24 dp; Translate, Analyze, Add term and Translation style now use 24 dp. Section labels in the
  XML dialogs are primary-coloured and bold (`TranslateSectionLabel`), like the Compose section
  headers and the Spending section titles. Notes are 13 sp grey (`TranslateNote`).
- **Theme correctness, fixed:** in the light theme the outlined text fields had an invisible
  outline and hint (Translate dialog From/To, glossary search) and the glossary filter chips were
  black blocks. `TranslateTextField` (outline and hint from `?attr/grayTextColor`, focus in primary)
  and `TranslateFilterChip` (same shape as the Compose FilterChips on Browse) fix both; checked in
  dark, black (Amoled) and light. In Amoled, `primaryGrayBackground` is black, so the floating
  buttons have no fill — that is the app-wide Amoled look (Downloads' sort button is the same).
- **Wide windows, fixed:** Settings stretched to 2448 px on the Fold inner screen; it is now centred
  and capped at 840 dp like the other content screens.
- **Spending, fixed:** title gets the spending icon; the three total cards are the same height;
  "Last 0 calls" is "Latest calls".
- **Glossary floating buttons, fixed:** a purple default FAB plus a plain text link; now two
  extended floating buttons in the app's style (Downloads sort button: `primaryGrayBackground`,
  bold text).
- **XML vs Compose:** the Translate, Analyze, glossary dialogs stay XML (AppCompat AlertDialog with
  the app's `Theme.AlertDialog`); settings, wizard and Spending are Compose M3. With the shared
  styles they now read alike (sentence-case buttons, primary section labels, same paddings). A
  full Compose port of the Translate dialog is not needed for cohesion.

### Flows

First run → key → first translation → glossary → spend, as a new user:

1. **Wizard.** Clear; step count, Skip, free connection test. Fixed: false "also per novel",
   the missing on-demand switch, the read-ahead name.
2. **Key in settings later.** The key dialog's check now uses the wizard's wording; the plain
   storage warning is the same string in both places.
3. **First translation.** Result page → Translate → dialog. The dialog now groups range /
   estimate / When / Glossary and says Translate. The automatic analysis hint names the setting
   correctly. Opening a chapter instead still shows the original silently when the switch is off
   or the key is missing (R5).
4. **Glossary.** Was a dead end (no menu, no title, no back button on a screen without the bottom
   bar). Fixed. The subtitle shows the original title, not the translated one (R2).
5. **Spending.** Reachable from settings and the wizard; caps are next to it in settings.

### Copy

- **Tone:** plain, second person, no exclamation marks ("API key is valid!" removed).
- **Capitalisation:** translation copy is sentence case. Upstream QuickNovel strings mix Title Case
  ("App Languages", "Check for Update", "Open In Browser", "Reload Chapter", "Search
  Downloads…") with sentence case ("App settings"); upstream strings were left alone to keep
  merges easy.
- **Errors and statuses** (one wording each):

| Case | Text | Where it comes from |
|---|---|---|
| Missing key | "No API key. Add one in Settings → AI translation." | `translator_no_api_key` (dialogs, service, analysis) |
| Invalid key | "The provider rejected this key. Check that it was copied completely and that it belongs to %s." | `setup_test_invalid_key` via `ConnectionMessages` (wizard and settings) |
| Provider blocked / region / captive portal / Cloudflare-like HTML | "Something blocked the connection to %s: a region restriction, a firewall or VPN, or a Wi-Fi login page." | `setup_test_blocked` |
| No connection | "Could not reach %s. Check your internet connection and try again." | `setup_test_network` |
| Rate limited | "Connected, but the provider is rate-limiting this key right now. It should work in a moment." | `setup_test_ok_rate_limited` |
| Provider error | "%s answered with an error unrelated to the key. Try again later." | `setup_test_provider_error` |
| Refused passage | fallback model wording: "passages that the translation model's provider blocks or refuses" | `tr_fallback_*` |
| Cap reached | `SpendCaps` message ("$4.50 spent this month + about $0.60 for this job = $5.10, cap $5.00. Translate fewer chapters or raise the cap in Settings → AI translation.") in a "Spend cap" dialog | `translator/SpendCaps.kt` (T1: "AI translation") |
| Charging policy | "Paused: waiting for charger", "Paused: battery low" | `translator/PowerPolicy.kt` (fine) |
| Stale | "Source changed" chip; "Also re-translate N chapters whose source changed" | `chapter_status_stale`, `translate_retranslate_stale` |
| Browse source failure / Cloudflare | "Unavailable right now" + Retry | `browse_unavailable` (fine) |

- **Number formats:** cost estimates used "~$0.13" (`CostEstimator.formatCost`) but per-100
  figures "≈ $0.29 / 100 ch" (`formatPer100`). All use "≈" now ("≈ $0.13 (≈ 45k tokens)", T3).

## Change list

### Part 1: done in this pass

| Commit | Change |
|---|---|
| 9e03b40 | One icon per concept (17 Material Symbols drawables); `ic_baseline_translate_24` = the Symbols translate glyph |
| 6356c6c | `strings_translate.xml` vocabulary; old values reworded (glossary, Read ahead, missing key, overnight options, stale); `ui/common/ConnectionMessages` + `ConnectionMessagesTest` |
| 20bdb2c | Settings → AI translation in seven sections, all strings shared, concept icons, Test connection with wizard messages, settings capped at 840 dp |
| 7037c42 | Wizard uses `ConnectionMessages`, "Read ahead", spending icon |
| cce3feb | Translate and Analyze dialogs: `styles_translate.xml`, section labels, From/To fields, strings, Translate button, model label in the analysis estimate |
| 06e2004 | Spending: strings, icon, equal cards, "Latest calls" |
| be85fc5 | Glossary toolbar with the menu, extended floating buttons, hidden empty review button, text field style, 24 dp dialog padding |
| f566c26 | Notifications: translate icon, "AI translation" channel, strings |
| c241111 | Glossary filter chips shaped like Browse's |
| fa8d880 | Wizard Done page: "Translate chapters when opened"; name-order examples for Korean too |

Behaviour is unchanged except: the glossary menu is now reachable, the empty "Review new terms"
button is hidden, and the wizard offers one more existing switch (default untouched).

### Part 2: done in pass two

| Commit | Items | Change |
|---|---|---|
| 5c8e2b1 | R1, R3b | Glossary button icon `ic_glossary_24`; ⋮ menu without the duplicate Glossary; 16 dp `ic_original_24` on Show original / Show translation |
| 4a2cf3c | R2, R3 | Glossary subtitle and export name use the displayed title (`ResultPageLogic.displayedTitle`, tested), also from the "Review new terms" snackbar; failure toast from `tr_job_failed` |
| 80c713f, 353bdf8 | R6 | Lookup sheet: `TranslateOutlinedButton` for Edit / Lock / Add to glossary (icons next to the text), `ic_glossary_24` on the title and on Add to glossary, sentence-case Approve / Dismiss |
| 111caab, 12d2256 | R4, R5, R6b | Reader Translate tab: AI translation section with the on-demand switch (same `SecurePrefs` value as Settings and the wizard; shown when `AbstractBook.translatesOnDemand`; a change reloads the loaded chapters that carry a notice); ML Kit heading "On-device translation (ML Kit)" (`reader_ml_translation_title`); Apply uses `ic_baseline_translate_24` and `translate_24px` is deleted. Original notice under the chapter title in scroll mode and on the paged title page (`ui/reader/OriginalNotice`, `OriginalNoticeTest`; new string `tr_reader_original_notice`). Title long-press on a translated chapter says "Show original" |
| cad4f2d | R7 | Preview sheet: "1431 chapters" via `result_chapter_count` |
| 439ec92 | T1, T2 | "Settings → AI translation", "per-job spend cap", SpendKind "Read ahead" |
| 2cba12d | T3 | "≈" for every estimate (`CostEstimatorTest` updated) |
| e734733 | T4 | `NovelAnalyzer.analyze(noKeyMessage = …)`; the service passes `translator_no_api_key` |
| f337778 | sweep | Glossary import error says "glossary export" |
| docs commit | R9 | `reader.md` (AI translation in the reader, lookup buttons, glossary wording), `result-page.md`, `browse.md`, `translation-pipeline.md` on the vocabulary |

Notes and deliberate skips:

- **R3c:** nothing to do; the XML result page was already deleted.
- **R5:** the notice follows the order of the checks in `getOrTranslate`: with the switch off it
  asks to turn it on (even without a key), with the switch on and no key it names the key. A
  failed translation (switch on, key set) gets no notice; that case was out of scope. Offline
  EPUBs of a source (stored translations only) get no notice, they never translate on demand.
- **R6b:** one menu item, not two: on a translated chapter "Show original" replaces "Reload
  Chapter" (same action, no "Reloading chapter" toast); on an original chapter it stays "Reload
  Chapter" (upstream string, Title Case kept). Paged mode has no title long-press (unchanged).
- **R7:** the preview sheet has no Show original toggle (the list assumed one); nothing to add.
  Library and search cards already pick "Chapter" / "Chapters" by count with upstream's Title Case
  strings and were left alone.
- **R8:** the only XML chip in these areas is the glossary filter chip, already on
  `TranslateFilterChip`.
- **L1:** kept (`LibraryUpdateNotifier` keeps the book icon).
- **Sweep:** no "AI Translation", "character sheet", "Verify" or "Read-ahead" left in user-facing
  text of these areas; "Story Bible" stays in code, prompts, the export file name and the JSON
  format. Title Case labels that remain ("Reload Chapter", "Open In Browser", "Stream Read", reader
  settings) are upstream strings, left as in pass one. The Translate dialog's AlertDialog buttons
  keep the platform letter spacing (pass one area, unchanged).
- **Verified** on emulators (phone 1080x2400 and Fold 8 inner 2448x1848, screenshots not
  committed): result page row and ⋮ menu, Show original icon, glossary subtitle from a translated
  title, reader Translate tab, both notices in scroll mode and the paged title page, Reload
  removing the notice, the lookup sheet (no match and a term), the preview sheet count, Spending,
  the "≈" estimate. Imported books open in the app's reader only with Settings → External reader
  off (the external path drops the translator id; unchanged).

### Part 2: the original list

Strings referenced below already exist (pass one added them or kept the keys). Old keys were not
renamed.

**Result page (`ui/result/`)**

- **R1** `ResultScreen.kt`, the Translate / Glossary / ⋮ row (around line 712): Glossary button icon
  `R.drawable.ic_baseline_menu_book_24` → `R.drawable.ic_glossary_24`. In the ⋮ `DropdownMenu`
  (around line 736) remove the duplicate item `R.string.result_glossary` (keep "Analyze novel…"
  `glossary_menu_analyze` and "Source language…" `imported_menu_language`).
- **R2** `ResultFragment2.kt`, the `OpenGlossary` effect: pass the displayed (translated) title as
  `novelName` to `GlossaryFragment.newInstance` when one is shown, so the glossary subtitle matches
  the page. (GlossaryFragment uses it only for the subtitle and the export file name.)
- **R3** `ResultFragment2.kt:104`: the hard-coded toast `"Translation error: ${progress.error}"` →
  `getString(R.string.tr_job_failed, progress.error)` ("Translation failed: %1$s"); the
  "Translation finished" snackbar is already from strings.
- **R3b** `ResultScreen.kt` Show original toggle (around line 760, `browse_show_original` /
  `browse_show_translation`): add `R.drawable.ic_original_24` as a 16 dp leading icon so the
  toggle reads as an action, as on Browse's preview sheet if R7 is done.
- **R3c** `ResultFragment.kt` / `ChapterAdapter` / `fragment_result.xml`: dead (being deleted by
  the cleanup agent); no copy work.

**Reader (`ui/reader/`, `ReaderBooks.kt`, `ui/TextAdapter.kt`)**

- **R4** `ReaderSettingsSheet.kt` + `layout/read_bottom_settings.xml`, tab `R.string.tab_translation`
  ("Translate"): it only holds upstream's ML Kit translation (`google_translate` "Google
  translate", `online_translate` "Online translation", default English → English). Proposal: rename
  the tab heading `read_ml_title` text to a new string "On-device translation (ML Kit)" (add it to
  `strings_reader.xml`; keep `google_translate` for other users), and add at the top of the tab an
  "AI translation" row with the `ic_baseline_translate_24` icon: a switch bound to
  `SecurePrefs.isTranslateOnDemandEnabled` titled `R.string.tr_on_demand` with
  `R.string.tr_on_demand_desc`, shown only for zh / ja / ko sources. Same setting as
  Settings and the wizard; no new preference. Replace `translate_24px` on `read_apply_translation`
  with `ic_baseline_translate_24` (same glyph; one resource).
- **R5** Silent fallback. `ReaderBooks.kt` `QuickBook.getChapterData` / `RegularBook` (imported):
  when `TranslationLanguages.langOfApi(...)` is zh/ja/ko and `getOrTranslate` returns null because
  `SecurePrefs.getApiKey(ctx)` is blank or on-demand is off, show one line above the chapter text
  (in `ChapterStartSpanned` or a small `TextSpan` styled like the "(AI translated)" title suffix):
  no key → `R.string.translator_no_api_key`; switch off → new string "Showing the original.
  Turn on Translate chapters when opened to translate it." (add to `strings_translate.xml` as
  `tr_reader_original_notice`). Do not show it when the user chose Reload (original on purpose).
- **R6** `layout/read_glossary_lookup.xml`: the "Add to glossary" / Edit / Lock buttons are filled
  white MaterialButtons with letter spacing; use `@style/TranslateOutlinedButton` (or
  `TranslateTextButton` for Edit/Lock) and `app:icon="@drawable/ic_glossary_24"` on "Add to
  glossary"; title row icon `ic_glossary_24`. Strings are already "glossary".
- **R6b** "Reload Chapter" (`reload_chapter`, upstream, Title Case) on the chapter-title long press
  is how imported books show the original; consider a dedicated "Show original" item using
  `browse_show_original` for translated chapters (same action).

**Browse (`ui/browse/`, `MainActivity` preview sheet)**

- **R7** Preview sheet (`MainActivity` `bindPreview` → `ui/common/MetadataTranslationViews.bindPreview`):
  chapter count reads "162 Chapter" (singular). Use `R.plurals.result_chapter_count`. The
  "Show original" toggle there is in `ui/common` and already uses `browse_show_original`.
- **R8** All sources chips (Compose `FilterChip`) and the glossary chips now match; keep new XML chips
  on `@style/TranslateFilterChip`.
- **R9** Docs were updated in this pass (setting names in `browse.md`, `result-page.md`,
  `translation-pipeline.md`, `setup-wizard.md`); keep new docs on the vocabulary above.

**Translator package (`translator/`, pure JVM)**

- **T1** `SpendCaps.kt:40`: "raise the cap in Settings → AI Translation." → "… → AI translation."
- **T2** `Spend.kt:13` `SpendKind.READ_AHEAD` label "Read-ahead" → "Read ahead" (Spending's
  "Latest calls" prints it). `BatchStatus` labels ("Queued", "In progress", "Storing results", …)
  are fine.
- **T3** `CostEstimator.formatCost` ("~$0.13") vs `formatPer100` ("≈ $0.29 / 100 ch"): pick one
  approximation sign (≈ reads better next to "Estimated cost:").
- **T4** `NovelAnalyzer.kt:140` error "No API key" → use the same message as
  `translator_no_api_key` (pass it in from the caller, the package has no resources).

**Library / notifications (read-only)**

- **L1** `updates/LibraryUpdateNotifier.kt` uses `ic_baseline_menu_book_24` as small icon — fine
  (reading, not glossary). Keep it off `ic_glossary_24`.

## Files

- `res/values/strings_translate.xml` — the vocabulary; `strings_setup.xml`, `strings.xml`,
  `strings_reader.xml`, `strings_translator_jobs.xml` — reworded values, keys unchanged.
- `res/values/styles_translate.xml`, `res/color/translate_*.xml` — XML dialog styles.
- `res/drawable/ic_*_24.xml` — concept icons (Material Symbols Outlined, Apache 2.0).
- `ui/common/ConnectionMessages.kt` — one wording for connection-test results (tested).
- `ui/settings/TranslationSettings.kt` — sections (`SectionHeader`), rows.
- `ui/reader/OriginalNotice.kt` — why the reader shows the original, and the notice line (tested).
