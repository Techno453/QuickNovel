# Translation pipeline: analysis, read-ahead, spend, battery, stale sources, overnight batches

How a novel goes from "never translated" to "chapters translated ahead of the reader", and what
keeps it cheap: the spend ledger and caps, charging conditions for long jobs, source-hash cache
invalidation and overnight Batch API pre-translation. Model
choice and costs are in [translation-models.md](translation-models.md); the benchmark is in
[translation-bench.md](translation-bench.md).

## Settings (Settings → AI Translation)

| Setting | SecurePrefs key | Default | Notes |
|---|---|---|---|
| Analyze novel before first translation | `auto_analyze_before_translate` | on | Runs the pre-pass once per novel before its first translation. |
| Chapters analyzed automatically | `auto_analyze_chapter_count` | 3 (1–20) | The manual Analyze novel keeps its own default (`analyze_chapter_count`, 5). |
| Analysis model | `analysis_preset` | absent = same as the translation model | Any preset, e.g. GPT-6 Sol. If that provider has no API key, the translation model is used. Applies to manual and automatic analysis. |
| Auto-translate ahead | `read_ahead_chapters` | 2 (choices 0, 1, 2, 3, 5, 10; 0 = off) | Keeps the next N chapters after the one being read translated. |
| Monthly spend cap | `monthly_spend_cap_usd` | 0 = off (float, USD) | See [Spend ledger](#spend-ledger-dashboard-and-caps). |
| Per-job cap | `per_job_cap_usd` | 0 = off (float, USD) | One range job or overnight batch. |
| Long jobs only while charging | `jobs_only_while_charging` | off | See [Charging and battery](#charging-and-battery-conditions-for-long-jobs). |
| Pause long jobs on low battery | `jobs_pause_on_low_battery` | on | |
| Low battery threshold (%) | `jobs_low_battery_percent` | 15 (5–50) | |

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

## Spend ledger, dashboard and caps

**Recording.** `TranslationEngine` reads `usage.prompt_tokens` / `usage.completion_tokens` from
every successful reply (translation, strict retries, fallback, summary merges, analysis, search,
key verification) and reports a `UsageEvent` to `UsageSinks.global`, which the app sets at start
(`BaseApplication.onCreate` → `SpendLedger.install`) to insert a row into `spend_entries`
(DB v6): timestamp, novel and chapter (nullable), kind, provider, model, tokens, USD, `priced`,
batch id. Plain JVM code (tests, the benchmark) has no sink, so nothing is recorded there.

- **What a call was for** comes from a `SpendTag` coroutine context element set by the caller:
  `translate` (reader on demand, range jobs, live re-dos of batch passages), `read_ahead`,
  `analysis` (`NovelAnalyzer` wraps every run), `search` (AI search queries), `batch` (one row per
  chapter of batch results), `other` (untagged: key verification).
- **Price:** the preset's own `priceIn` / `priceOut`; for a custom model the built-in price table
  (`CostEstimator.pricingFor`, matched by model id). Custom models have no user-entered price in
  the app, so an unknown model is recorded with `usd = 0` and `priced = false` (the dashboard
  counts them). A reply without `usage` is recorded with 0 tokens and `priced = false`.
- **Batch results** are recorded per chapter from the per-line `usage` of the output file × the
  list price × `SpendPricing.BATCH_DISCOUNT` (0.5). The batch object has no usage field; failed
  lines are not billed and carry none.
- Deleting a novel keeps its spend rows (money already spent).

**Dashboard** (Settings → AI Translation → Spending, `ui/translate/SpendScreen.kt`, a full-screen
Compose dialog): today / this month / all time (local calendar), per novel (top 10 + "everything
else"), per model, the last 50 entries, and Clear history (with confirmation; this also resets
the month for the cap). Opening it also polls overnight batches. The translate dialog shows
"Spent this month: $x (of the $y cap)" under its estimate.

**Caps** (`SpendCaps`, pure, `SpendCapsTest`): 0 = off.

| When | Rule |
|---|---|
| Start of a range job (dialog) or a batch (dialog, then again in `BatchManager.submit` with the precise estimate) | refused if month's spend ≥ monthly cap, or month's spend + estimate > monthly cap, or estimate > per-job cap. The message names the numbers ("$4.50 spent this month + about $0.60 for this job = $5.10, cap $5.00"). An unknown-price estimate counts as $0. |
| Range job, between chapters | stops when the month's spend reaches the monthly cap or the job's own spend (a `SpendMeter` in the job's `SpendTag`, including its analysis) reaches the per-job cap. The summary notification starts with the reason. |
| Read-ahead, between chapters | clears its queue silently at the monthly cap (logged only). |
| Batch results | live re-dos of failed passages only while under both caps (the processing has its own meter); otherwise the chapter is left for the normal path. |
| Reader on-demand (chapter being read) | never blocked; its spend is recorded and counts towards the month. |

Granularity is one chapter (a chapter in progress finishes), so a job can pass a cap by at most
one chapter's cost.

## Charging and battery conditions for long jobs

`PowerPolicy` (pure, `PowerPolicyTest`) decides from `PowerState` (charging, level %, system
low flag):

- "only while charging" on and not charging → **Paused: waiting for charger**;
- "pause on low battery" on, not charging and level ≤ threshold (default 15%) → **Paused:
  battery low** (the system's ACTION_BATTERY_LOW only counts when the level is unknown, so a
  lower user threshold is respected; while charging a low battery never pauses).

`PowerMonitor` (owned by `TranslationForegroundService`) reads the sticky
ACTION_BATTERY_CHANGED when the service starts and follows ACTION_POWER_CONNECTED /
DISCONNECTED, ACTION_BATTERY_LOW / OKAY and level changes while the service runs. Range jobs
and read-ahead call `awaitAllowed` between chapters: the notification shows the pause reason and
the job resumes on its own when the condition clears (the policy is re-read every minute, so
settings changes apply). On-demand translation of the chapter
being read never goes through the service and is never paused. The overnight option for
providers without a batch API starts a range job with `requireCharging` (the policy with
"only while charging" forced on).

## Source-hash cache invalidation

Every stored translation now has `source_hash` = SHA-256 hex of the normalised plain source
(`SourceHash`: HTML → text, NFC, whitespace runs collapsed, lines trimmed, blank lines dropped;
`SourceHash.VERSION` = 1) and a `stale` flag (DB v6; rows from before have `""` and are treated
as valid).

- **Writing:** reader on-demand, read-ahead, range jobs and batch results store the hash of the
  source they translated.
- **Checking** (`ChapterTranslationRepository.checkSourceHash`), only where the source was fetched
  anyway, never to check: the analysis pre-pass (chapters 0..N-1) and range jobs re-translating
  stale chapters. A different hash marks the translation `stale` (still served; never
  re-translated automatically); a matching hash clears the flag; an empty stored hash is filled
  in (assumed valid).
- **Re-translating:** the translate dialog shows "Re-translate N stale chapter(s) in this range"
  when the range has some. Those chapters' sources are fetched and compared again first: if the
  source is back to the stored hash, the translation is kept and the paid call skipped.
  `translateIfMissing(retranslateStale = true)` does the same for single chapters.
- **Reader:** the reader does not show the flag yet (another change owns the reader UI). It can
  use `ChapterTranslationRepository.isStale(ctx, novelId, chapter)`,
  `staleChapters(ctx, novelId)` or the live `observeStaleChapters(ctx, novelId): Flow<List<Int>>`
  for a "source changed" hint, and should call `checkSourceHash(ctx, novelId, chapter, source)`
  after its Reload action fetched the original (cheap: one hash and one DB read).

## Overnight batch (OpenAI Batch API)

"Translate the rest of this novel tonight at half price." Offered in the translate dialog as
**Overnight (batch, ~50% off, ready within 24 h)** when the selected model's base URL is
api.openai.com and an OpenAI key is set (`BatchManager.isAvailable`). For any other provider
(OpenRouter, Gemini, DeepSeek, Qwen, custom) the option reads **Overnight (runs only while
charging)**: a normal range job at the normal price that waits for a charger, and the dialog says
so.

### Flow

1. **Submit** (`TranslationForegroundService` `ACTION_START_BATCH`, main job slot): automatic
   analysis first if the novel has none (`NovelAnalyzer.ensureAnalyzed`, respecting its setting),
   then fetch the sources of the range's chapters that are not translated and not already in an
   unfinished batch (progress in the notification and on the result page's button).
2. **Build** (`BatchRequestBuilder`, pure): snapshot of the Story Bible, premise and style; the
   live chunker (`TranslationEngine.splitIntoChunks`, `CHUNKER_VERSION` = 1); one request per
   chunk with the live system prompt (and the preset's prompt profile, extra body and temperature
   rule), the snapshot glossary selection (`selectPromptTerms` with the chunk's own terms plus
   characters seen in earlier chunks of the same chapter), and no previous-English tail or
   previous-chapter summary. `custom_id = "<novelId>|<chapter>|<chunkIndex>"` (parsed from the
   right, so a '|' in the novel id is safe; OpenAI documents no length or character limit, and a
   254-character Japanese custom_id was accepted and returned byte-for-byte in the live check
   below). The chunk texts and the chapter's source hash are
   stored in `batch_chapters`, so reassembly never re-derives the chunks.
3. **Cap check** with the precise estimate (live per-chapter estimate on the real character
   counts × 0.5), then `POST /v1/files` (purpose `batch`, JSONL), `POST /v1/batches`
   (`endpoint: /v1/chat/completions`, `completion_window: 24h`). The job is stored in
   `batch_jobs` (status, OpenAI ids, chapter range, request count, estimate) with every chapter
   PENDING, and a notification says it is queued.
4. **Poll** (`BatchPollWorker`, WorkManager): periodic every 15 minutes with a network
   constraint while a batch is unfinished (cancelled when none are left), plus a one-off poll on
   app start, when the translate dialog opens and when the Spending screen opens. All polls are
   serialized. Status mapping (`BatchStatus.fromOpenAi`): validating → Queued, in_progress,
   finalizing, cancelling, failed; completed / expired / cancelled with an output **or error**
   file → PROCESSING (results still to store), then DONE / EXPIRED / CANCELLED; with neither
   → FAILED / EXPIRED / CANCELLED. Requests that got an HTTP error are written to the error
   file only, so a batch whose every request failed ends `completed` with just an error file;
   it is still processed so each chapter is marked NEEDS_LIVE with OpenAI's reason. A PREPARING
   row older than two hours without an OpenAI id (killed process) becomes FAILED.
5. **Store** (`BatchManager.processResults`): download the output and error files, parse
   (`BatchOutputParser`), reassemble per chapter in chunk order (`BatchAssembler`: a success line
   wins over an error line; `finish_reason` length / content filter, empty replies, a
   `message.refusal` without content and HTTP errors are failures; absent lines are missing). Then, **in chapter order** and under the per-chapter
   lock, each PENDING chapter goes through `BatchChapterProcessor`, which runs the live chapter
   loop (`TranslationEngine.assembleChapter`) with the batch replies: the `===TERMS===` tail is
   parsed and merged into the **live** bible with BibleMerger (so later chapters see names found
   in earlier ones, as far as the post-processing goes), and QualityChecks run per chunk.
   - a missing / failed / cut-off / blocked / refused chunk is re-translated live (full live path
     with the fallback model, at the normal price, tagged `translate` with the batch id); a live
     re-do sees the previous chunk's English;
   - a chunk that fails the checks in strict mode gets the live strict re-request with the
     reminder; the better attempt is kept;
   - a chapter with no successful line at all (expired / cancelled before it ran) is not re-done
     live: it is marked NEEDS_LIVE and left to the normal path, so an expired batch never turns
     into a surprise full-price run;
   - when a passage cannot be produced (live re-do refused by a cap, failed, or no key) the
     chapter is NEEDS_LIVE; otherwise it is stored (with its source hash and the .txt for the
     offline epub) as STORED; a chapter translated meanwhile by another path is SKIPPED.
   Each chapter's batch usage is recorded as `batch` spend at half price; the job's `actual_usd`
   is everything recorded with its batch id (batch + live re-dos). A notification reports the
   outcome. Processing is resumable: chapters keep their status, so a killed worker continues
   on the next poll.
6. **Status row:** the translate dialog shows the novel's latest batch (unfinished, or finished
   in the last three days): status, n/m requests, chapters stored / left, estimate or cost,
   **Cancel batch** (`POST /v1/batches/{id}/cancel`; finished passages are still stored and
   billed) and **Translate the rest now** (fills in the batch's range for a normal job). Range
   jobs skip chapters that are waiting in an unfinished batch; reader on-demand and read-ahead do
   not wait for a batch (the reader comes first; the batch result for such a chapter is then
   SKIPPED but was still billed).

### Quality trade-off

The live pipeline is sequential; a batch is not. Compared with a live range job, a batch request
does not see:

- names discovered earlier in the same run (in earlier chapters or earlier chunks of the same
  chapter) — only the bible snapshot taken at submission. This is why the analysis runs first;
  the post-processing still merges every chunk's discovered terms into the live bible and checks
  each chunk against it, so a name first seen in the batch can show up as a glossary deviation
  and trigger a strict re-request;
- the previous chunk's English (the "preceding" context that keeps pronouns and tone
  continuous across chunk boundaries) and the previous chapter's summary;
- the strict-mode retry inside the batch: it happens afterwards, live, at full price, only for
  chunks that failed the checks.

Expect slightly more glossary deviations and pronoun slips at chunk and chapter boundaries for
names introduced after the analysed chapters; for a novel that has been analysed (or partly
translated) the difference should be small. Not measured at scale yet: the live check below
translated a single 612-character passage (13/13 paragraphs, no glossary deviation).

### Verified against the live API on 2026-10-12

`BatchLiveTest` (app/src/test/.../bench/) ran the app's own classes against api.openai.com with
gpt-6-luna on 2026-10-12 (UTC). Three batches:

- **A** (2 requests): a 612-character slice of the doupo sample (13 paragraphs, two locked
  bible terms, custom_id `Ixdzs/斗破苍穹|live|0|0`, `max_completion_tokens` 1,200, metadata with a
  Chinese value) plus one request with `temperature: 5`. Validating ~1 min, completed ~3 min after creation.
  The chapter came back in the output file, `finish_reason` stop; `BatchChapterProcessor`
  (no live calls allowed) produced 13 English paragraphs, no `===TERMS===` / `===SUMMARY===`
  residue, no glossary violation, a summary, and three discovered terms (乌坦城 → Wutan City,
  斗之力 → Dou Qi, 族长 → clan leader). The bad request came back **in the error file** as
  `{"id","custom_id","response":{"status_code":400,"request_id","body":{"error":{...}}},"error":null}`
  and was classified as a failed chunk with OpenAI's message, unbilled. 1,277 input + 641 output
  tokens; the per-line usage matched the batch object's `usage` exactly.
- **B** (1 request): custom_id of 254 characters / 734 UTF-8 bytes (Japanese novel id with '|')
  — accepted and returned unchanged; 13 + 4 tokens.
- **C** (1 request): cancelled right after creation while `validating`. It stayed `cancelling`
  for about 10 minutes before `cancelled`, with no output or error file, `request_counts` all 0
  and zero usage (no charge).

Total spend: **$0.000226** (A $0.000224, B $0.000002, at $0.05 / $0.25 per 1M batch price).
Output lines matched the documented shape (`{"id","custom_id","response":{"status_code",
"request_id","body":{chat.completion}},"error":null}`; the message also carries `refusal` and
`annotations`, usage carries `prompt_tokens_details.cached_tokens`, which the app does not
discount, so cost is slightly overstated when caching applies). The unit-test fixtures in
`BatchTest` follow these shapes.

Run it again (spends well under a cent; needs `OPENAI_API_KEY` in the environment or
`bench.local.properties`, and `app/build/translation-bench/cache/sample-zh-doupo.json` from a
bench run; otherwise it skips):

    ./gradlew :app:testDebugUnitTest -Dlive.batch=1 --tests "com.lagradost.quicknovel.bench.BatchLiveTest"

(`LIVE_BATCH=1` in the environment works too.) It polls every 20 s for at most 25 minutes,
cancels any of its batches still running at the end, lists the five most recent batches, and
writes the raw API responses (never the key) to `app/build/batch-live/<timestamp>/`.

### Limits

50,000 requests and 200 MB per batch (about 15,000 chapters; not enforced in the app);
OpenAI's per-model enqueued-token limit can reject large batches at validation (the batch then
shows FAILED with OpenAI's message, e.g. `token_limit_exceeded`). Deleting a novel removes its
finished batch rows but keeps unfinished ones, so their cost is still recorded when they end.

## Files

- `translator/Spend.kt` (kinds, tags, meter, pricing, periods), `SpendCaps.kt`, `SpendLedger.kt`.
- `translator/PowerPolicy.kt`, `ui/translate/PowerMonitor.kt`.
- `translator/SourceHash.kt`; hash checks in `ChapterTranslationRepository`.
- `translator/batch/`: `BatchModel.kt` (statuses, custom ids, outcomes), `BatchRequests.kt`
  (builder, JSONL, cost, output parser, assembler), `BatchChapterProcessor.kt`,
  `OpenAiBatchClient.kt`; `ui/translate/BatchManager.kt`, `BatchPollWorker.kt` (worker,
  scheduler, notifications).
- `ui/translate/SpendScreen.kt`; settings rows in `ui/settings/TranslationSettings.kt`.
- DB v6 (`MIGRATION_5_6`, additive): `translated_chapters.source_hash` / `stale`,
  `spend_entries` (+ index on timestamp), `batch_jobs`, `batch_chapters`.
- Tests: `SpendCapsTest`, `SpendLedgerTest`, `PowerPolicyTest`, `SourceHashTest`, `BatchTest`,
  `TranslatorDatabaseMigrationTest` (also compares the migration with Room's generated schema).
- `translator/AuthorNotes.kt`, `AnalysisFilter.kt`, `AnalysisRunner.kt`: pure analysis logic.
- `translator/NovelAnalyzer.kt`: DB + engine wiring, single-flight, analysis state.
- `translator/OncePerKey.kt`: the single-flight gate.
- `translator/ReadAhead.kt`: `ReadAheadPlanner`, `ReadAheadQueue`.
- `ui/translate/ReadAheadScheduler.kt`: reader hook → queue → service.
- `ui/translate/TranslationForegroundService.kt`: batch job, manual analysis, read-ahead worker.
- Tests: `AuthorNotesTest`, `AnalysisFilterTest`, `AnalysisRunnerTest`, `OncePerKeyTest`,
  `ReadAheadTest`, `TranslatorDatabaseMigrationTest`, `TranslationProtocolTest`.
