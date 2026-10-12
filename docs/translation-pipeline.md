# Translation pipeline: analysis pre-pass and read-ahead

How a novel goes from "never translated" to "chapters translated ahead of the reader". Model
choice and costs are in [translation-models.md](translation-models.md); the benchmark is in
[translation-bench.md](translation-bench.md).

## Settings (Settings → AI Translation)

| Setting | SecurePrefs key | Default | Notes |
|---|---|---|---|
| Analyze novel before first translation | `auto_analyze_before_translate` | on | Runs the pre-pass once per novel before its first translation. |
| Chapters analyzed automatically | `auto_analyze_chapter_count` | 3 (1–20) | The manual Analyze novel keeps its own default (`analyze_chapter_count`, 5). |
| Analysis model | `analysis_preset` | absent = same as the translation model | Any preset, e.g. GPT-6 Sol. If that provider has no API key, the translation model is used. Applies to manual and automatic analysis. |
| Auto-translate ahead | `read_ahead_chapters` | 2 (choices 0, 1, 2, 3, 5, 10; 0 = off) | Keeps the next N chapters after the one being read translated. |

Code: `SecurePrefs` (keys and getters), `TranslationSettings` (Compose rows).

## The analysis pre-pass ("Analyze novel")

Reads the first chapters, without translating them, and seeds the Story Bible (characters with
gender, notes and aliases; places, sects, ranks, techniques, items) plus the premise.

Flow (`NovelAnalyzer` → `AnalysisRunner`):

1. Fetch chapters 0..N-1 and convert to plain text.
2. Strip author's notes from each chapter (`AuthorNotes`), then split into requests of about
   8,000 characters (`CostEstimator.ANALYSIS_CHARS_PER_CALL`).
3. One extraction call per request (`TranslationEngine.extractStoryBible`, temperature 0.1).
   The prompt (`PromptTemplates.analysisSystemPrompt`) asks for proper nouns and
   setting-specific terms only, one entry per entity with variants as aliases, nothing from
   author's notes, and a `"proper": true|false` flag per entry. `TranslationProtocol.parseAnalysis`
   reads it; older replies without the flag still parse.
4. Clean the extraction with `AnalysisFilter` (below), then add new terms (auto-added,
   unlocked). An entry whose English equals an existing entry's English goes to pending review
   as a possible alias. Terms already in the bible are never changed.
5. On success (at least one extraction call answered) set `novel_context.analyzed_at` and
   `analysis_chapters`, and update the premise.

### Author's notes (`AuthorNotes`)

Detected per chapter, used only for analysis (translation input is never stripped):

- block markers that start a note running to the end of the chapter: 作者的话, 作者有话说,
  作者有话要说, 作者感言, 作者留言, 作者按, あとがき, 後書き, 作者コメント, 作者より,
  작가의 말, 작가 후기 (near the start of a paragraph, after brackets or dashes);
- single-paragraph notes: `PS:` / `ps：` / `P.S.` at the start; 求月票, 求推荐, 求收藏, 求订阅,
  求打赏, 推荐票, 月票, 票票, 书友 anywhere; Korean donation thanks (후원 at the start,
  후원 감사, 후원해 주신; not the in-story 후원자 "sponsor");
- a paragraph that opens with a round bracket `（` / `(` and mentions 书友, 新书, 收藏, 票票,
  推荐票, 月票, 求票, 码字, 打赏 or 订阅 is a note up to its closing bracket, even across
  paragraphs. This is how 斗破苍穹 chapter 1 ends (the pen name 土豆, 四川 and the previous book
  剑圣). `【…】` is not treated this way because system messages use it.

Pen names cannot be known in advance; they are caught because they sit inside such a note.

### Filter rules (`AnalysisFilter`)

Applied to every extraction before saving, in this order:

1. **Protected:** an entry whose source or alias is already in the bible (locked or not) passes
   through untouched and is never merged.
2. **Model said no:** `"proper": false`.
3. **Generic role:** the source is one of a small list of role words in any category
   (中年男子, 测验人, 测验员, 老者, 少年, 刺客, 大臣, 侍女, 暗殺者, 黒幕, 近衛侍女, 그 여자, 그년,
   남자, 노인, 암살자 … see `GENERIC_ROLES`), or a demonstrative/descriptive phrase (那个…, 这位…,
   その…, 例の…, 先輩の…, 그 …, 저 …), or a *character* whose English is not a name (has an
   article or no capital letter: "the minister", "examiner", "that woman").
4. **Common noun:** a non-character entry
   - with a very short source (1–2 Han/kana characters or 1–2 Hangul syllables) that does not
     look like a proper noun (all-katakana, or ending in a place/sect/family suffix such as
     城 国 宗 门 派 山 家 族 域 領 村) and has confidence below 0.8 or an all-lowercase English
     rendering (毒 "poison", 麦畑 "wheat fields", 低级 "low level", 天才 "prodigy", 신 "god",
     차원 "dimension"), or
   - that the model rendered as a single lowercase English word ("scroll", "pigeon",
     "reincarnation", "adventurer").
5. **Author's note only:** every form of the entry occurs only inside author's notes of the
   excerpt (土豆, 四川, 剑圣 above).
6. **Merge:** entries with the same source are merged (the most confident wins; gender, notes
   and aliases are filled in from the others), then entries of the same category with the same
   English rendering (case, spacing, hyphens, apostrophes and a leading "the" ignored) become one
   entry; the other sources become aliases. The fuller (longer) source wins a confidence tie.

Confidence keeps its meaning (the model's value; a merged entry takes the maximum). The lists
are deliberately small; the prompt does most of the work. What is still left to the prompt:
capitalised common nouns from models that title-case everything (Luna run 2: "Poison",
"Amniotic Fluid"), "Miss" (小姐), and invented insults such as 니애미 차원.

Measured offline on the 12 saved extractions of the 2026-10-11 Luna/sol comparison (old
prompt, filter only, no API calls): 189 entries → 130, every named character kept; all of the
author-note artifacts, unnamed roles and most short common nouns listed in the comparison were
removed. The new prompt itself has not been benchmarked yet.

### When the analysis runs

| Path | Trigger | Chapters fetched for analysis reused? | Progress |
|---|---|---|---|
| Manual Analyze novel (Glossary menu, result page long-press, translate dialog hint) | always runs, re-run allowed; sets the state | n/a | Glossary screen + notification |
| Batch translation (Translate dialog → `TranslationForegroundService` `ACTION_START`) | novel never analyzed and the setting is on | yes (in-memory cache for the job) | notification "Analyzing novel before translating", result-page button "Analyzing novel… n/m", Glossary screen |
| Read-ahead worker | same, before its first chapter of the novel | yes | read-ahead notification "Analyzing novel… n/m" |
| Reader on-demand translation (`ChapterTranslationRepository.getOrTranslate`) | same | yes (the current chapter; others come from the reader's chapter file cache) | toast "Analyzing the novel before its first translation…" |

- Single-flight per novel (`OncePerKey`): concurrent callers (reader preloading several
  chapters, the service) wait for the one running analysis and then continue with the fresh
  bible instead of analyzing again.
- Failure (network/API error, no chapter could be loaded): logged, nothing is marked, the
  translation goes on without it. Callers that were waiting for the failed run do not retry
  immediately; the next translation start retries.
- The translate dialog adds "Includes analyzing the first N chapters first: ≈ $x" to its
  estimate when the analysis will run.
- DB: `novel_context.analyzed_at` (epoch ms, 0 = never) and `analysis_chapters`, added in
  `TranslatorDatabase` v5 (`MIGRATION_4_5`). Novels that already had a premise when upgrading
  count as analyzed. The state is not part of the Story Bible JSON export.

## Read-ahead ("Auto-translate ahead")

When the reader moves to chapter *i* (`ReadActivityViewModel.changeIndex` → `AbstractBook.onChapterOpened`
→ `ReadAheadScheduler`), and the novel is being read in translation, chapters *i+1 … i+N* that
are not translated yet are queued and translated in the background by the same foreground
service as batch jobs.

- **Being read in translation:** a Chinese/Japanese/Korean source, an API key for the
  translation model, and either "Translate chapters on demand" is on or chapter *i* already has
  a stored translation (e.g. batch-translated earlier).
- **Queue computation** (`ReadAheadPlanner.plan`, pure, unit-tested): `(i+1 .. min(i+N, last))`
  minus chapters with a stored translation minus chapters being translated right now. Never at
  or behind the current chapter. Each chapter change replaces the pending list with a fresh plan
  (`ReadAheadQueue.update`), so the queue is topped up as you read and chapters that fell behind
  or outside the window (after a jump) are dropped. One novel at a time: opening another novel
  replaces the queue.
- **Worker** (`TranslationForegroundService`, `ACTION_READ_AHEAD`): low priority. It takes
  chapters in order, runs the analysis first if the novel was never analyzed, then translates
  with the same engine, Story Bible, context and persistence as on-demand translation. Between
  chapters it pauses when a batch translation or manual analysis holds the main job slot and is
  resumed when that job finishes. It keeps running after the reader is closed, and stops when
  the queue is empty, when read-ahead is set to 0 (the queue is cleared), or from the
  notification's **Stop** action (clears the queue and cancels the chapter in progress).
- **De-duplication:** every single-chapter path (reader on demand, read-ahead, batch job)
  translates under the same per-chapter lock (`ChapterTranslationRepository.withChapterLock`)
  and re-checks the database inside it, so a chapter is never translated twice; the reader
  waits for a read-ahead translation of the chapter it opens and then shows it.
- **Cost:** read-ahead never asks for confirmation (N ≤ 10 chapters at the current chapter
  price); the batch translate dialog keeps its estimate. Stored translations count like any
  other (same `translated_chapters` rows, model id and quality counters).
- **Reader preload:** the reader itself already preloads a few chapters around the current one
  and, with on-demand on, translates them; read-ahead adds a queue that survives closing the
  reader and covers chapters the preloader has not reached. Both share the chapter lock.

## Files

- `translator/AuthorNotes.kt`, `AnalysisFilter.kt`, `AnalysisRunner.kt`: pure analysis logic.
- `translator/NovelAnalyzer.kt`: DB + engine wiring, single-flight, analysis state.
- `translator/OncePerKey.kt`: the single-flight gate.
- `translator/ReadAhead.kt`: `ReadAheadPlanner`, `ReadAheadQueue`.
- `ui/translate/ReadAheadScheduler.kt`: reader hook → queue → service.
- `ui/translate/TranslationForegroundService.kt`: batch job, manual analysis, read-ahead worker.
- Tests: `AuthorNotesTest`, `AnalysisFilterTest`, `AnalysisRunnerTest`, `OncePerKeyTest`,
  `ReadAheadTest`, `TranslatorDatabaseMigrationTest`, `TranslationProtocolTest`.
