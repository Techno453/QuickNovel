# Translation benchmark

A JVM benchmark that runs the app's real `TranslationEngine` (chunking, prompts, glossary
injection, retries) against several LLMs on Chinese / Japanese / Korean web-novel samples and
writes a side-by-side report with cost, quality and consistency metrics.

Code: `app/src/test/java/com/lagradost/quicknovel/bench/`
Config: `app/src/test/resources/bench/bench.config.json`

It is a JUnit test (`TranslationBench`) that is **skipped** unless `BENCH=1` (environment) or
`-Dbench=1` is set, so `./gradlew :app:testDebugUnitTest` never calls an API.

## Run it

```sh
BENCH=1 ./gradlew :app:testDebugUnitTest --tests "com.lagradost.quicknovel.bench.TranslationBench" -Dbench=1 --console=plain
```

(`ANDROID_HOME` / `JAVA_HOME` as for a normal build. Drop `-q` so the leaderboard printed at
the end is visible; it is also in the report.)

Overrides, as `-Dbench.<name>=…`, environment `BENCH_<NAME>=…`, or a line in
`bench.local.properties`:

| Setting | Example | Effect |
|---|---|---|
| `bench.models` | `-Dbench.models=gpt-5-mini,anthropic/claude-haiku-5.5` | run only these model ids (must exist in the config) |
| `bench.samples` | `-Dbench.samples=zh-doupo` | run only these sample ids |
| `bench.dryRun` | `-Dbench.dryRun=true` | fetch samples, fake outputs, compute metrics, write the report, and run every enabled model config through `BenchEngineAdapter` against a local fake endpoint; **no API calls** |
| `bench.judge` | `-Dbench.judge=false` | skip all LLM judging |
| `bench.judges` | `-Dbench.judges=gpt-5.4-mini,deepseek/deepseek-v4.1-flash` | run only these judges (ids from `judge.model` / `judges.panel`) |
| `bench.reportOnly` | `-Dbench.reportOnly=true` | no API calls: rebuild the report from stored results, stored judge scores and manual judge files (metrics recomputed) |
| `bench.rejudge` | `-Dbench.rejudge=true` | judging only, on stored outputs: no smoke test, bible or translation calls; cells already scored are skipped |
| `bench.analyzeOnly` | `-Dbench.analyzeOnly=true -Dbench.analyzeModels=gpt-6-luna,gpt-6-sol -Dbench.analyzeRuns=2` | Story Bible pre-pass only: runs the app's "Analyze novel" prompt with each listed model on every sample, `analyzeRuns` times each, and writes `<run>/analysis/*.json` plus `<run>/analysis-compare.md`; no translation or judging |
| `bench.budget` / `BENCH_BUDGET_USD` | `BENCH_BUDGET_USD=1.50` | cost cap (default 2.50) |
| `bench.parallelism` | `-Dbench.parallelism=2` | concurrent translations (default 4) |

Smoke check after changing the engine, presets or the config (free; only the sample pages are
fetched):

```sh
BENCH=1 ./gradlew :app:testDebugUnitTest --tests "com.lagradost.quicknovel.bench.TranslationBench" -Dbench=1 -Dbench.dryRun=true -Dbench.judge=false --console=plain
```

It logs `adapter smoke <model>: N requests, ok` per enabled model and fails if a request does not
match the model's resolved config (endpoint, model id, extra body, temperature, headers).
`BenchConfigPresetTest` (part of the normal unit tests) checks preset resolution and the adapter
offline as well.

Re-running a subset (e.g. one model after fixing its parameters) merges into the report: the
latest result per (sample, model) is kept in `app/build/translation-bench/results/` and every
report is built from those, so never re-run everything to fix one model.

## Judge panel

Every output is scored by a panel of LLM judges: `judge.model` (the original single judge,
gpt-6-luna) plus the models in the top-level `judges.panel` block of the config. Panel entries
take the same fields as `models` entries (prices, `baseUrl`, `keyName`, `headers`, `extraBody`,
`reasoningEffort`), so each judge goes through the same request plumbing and cost metering. The
default panel spans three providers with reasoning off: `gpt-5.4-mini` (OpenAI,
`reasoning_effort: none`), `google/gemini-3.1-flash-lite` and `deepseek/deepseek-v4.1-flash`
(OpenRouter, `reasoning: {enabled: false}`). `judges.temperature` (default 0) is sent where the
provider accepts it and dropped automatically when rejected; `judges.maxCompletionTokens` caps the
reply (default 300). Panel entries are resolved like `models` entries: one without `keyName` is an
OpenAI model (before this was fixed, gpt-5.4-mini stored "no key ()" errors instead of scores).

- **Blind rubric.** The judge sees the source excerpt, the story bible the translator was given and
  the translation, never the model name. It returns JSON with integer 1–10 scores for
  **accuracy** (meaning preserved, nothing omitted or added), **fluency** (natural English) and
  **consistency** (bible names/terms and genders followed; capitalization-only differences do not
  count) plus a one-sentence note. Replies are parsed robustly (code fences, prose around the
  object, `"8/10"`, `terminology` for `consistency`, clamped to 1–10); a provider that rejects JSON
  mode is asked again without it.
- **Self-judging exclusion.** A judge never scores its own model's output. Ids are compared without
  provider prefix and case (`google/gemini-3.1-flash-lite` == `gemini-3.1-flash-lite`). Those
  cells are filled by the remaining judges only and listed in the report. The v1 judge's stored
  self-scores are kept only for the "luna-only" comparison column.
- **Aggregation.** Per output: each judge's score (mean of the three criteria), the panel mean and
  the spread (max − min). Per model: **Panel adj.** (primary; the leaderboard is sorted by it),
  **Panel raw**, the **v1-only** mean (the old single-judge leaderboard, self-score included), each
  judge's mean, and the v1 → panel rank change (⚑ = 3+ places). *Panel adj.* first removes each
  judge's leniency bias (its mean difference from the other judges on the same outputs, scaled so
  the biases sum to zero); where every judge scored an output it equals the raw mean, and where a
  judge is missing (its own model) it stops a strict or lenient judge's absence from moving that
  model.
- **Agreement.** The report adds a judge table (outputs scored, mean given, leniency bias, spend),
  a Spearman rank-correlation matrix of the judges' model rankings (over the models both judges
  scored, n shown), the models whose rank moves 3+ places with every judge's note, the outputs
  with the largest spread, and a generated "What changed vs single-judge" paragraph.
- **Stored scores.** Panel scores live in `app/build/translation-bench/judges/<judge>/<sample>/<model>.json`
  with the translation's run id and a hash of the exact prompt. A newer translation or a changed
  prompt (rubric, bible, source) makes a score stale. v1 scores stored in the results count as the
  `judge.model` judge's, so gpt-6-luna is never re-paid.

### Rejudge

```sh
BENCH=1 ./gradlew :app:testDebugUnitTest --tests "com.lagradost.quicknovel.bench.TranslationBench" -Dbench=1 -Dbench.rejudge=true --console=plain
```

Judges the stored outputs only. For every (judge, sample, model) cell that has no current score
it builds the prompt, estimates the cost from its token count and the judge's prices, logs the
per-judge projection, and drops the most expensive judge while `ledger + projection > budget`
(pass `-Dbench.budget=…` to cap a single rejudge). A second run makes no calls. Bibles must be
cached (rejudge never seeds them).

Cost: one judgment is about 8–9k input tokens and ~150 output. The default panel over 16 models ×
3 samples (45 calls per judge after self-exclusion) measured **$0.25** on 2026-10-11:
gpt-5.4-mini $0.167, gemini-3.1-flash-lite $0.053, deepseek-v4.1-flash $0.028. In a normal run
the panel judges each new output right after it is translated (included in the per-phase
estimate).

### Manual judges

Human scores join the panel from `app/build/translation-bench/manual-judges/<judge-name>.json`:

```json
[
  { "sample": "zh-doupo", "model": "gpt-6-luna", "accuracy": 8, "fluency": 7, "consistency": 9, "note": "One-sentence note." }
]
```

`sample` is a sample id and `model` a model id exactly as in the config; scores are 1–10 (clamped).
Each file is one judge named after the file and shown as "<name> (manual)" in every per-judge
column, the panel means, the spread and the Spearman table; it may cover any subset of outputs.
The self-exclusion rule applies if the name matches a model id. Unreadable files are reported in
the notes and ignored. `-Dbench.reportOnly=true` merges them with no API calls.

## API keys

Put keys in `bench.local.properties` at the project root (gitignored via `*.local.properties`):

```
OPENAI_API_KEY=sk-...
OPENROUTER_API_KEY=sk-or-...
# only for direct-provider presets:
GEMINI_API_KEY=...
DEEPSEEK_API_KEY=...
DASHSCOPE_API_KEY=...
ANTHROPIC_API_KEY=...
```

or set them as environment variables (these win). From a git worktree the harness also finds
the main checkout's `bench.local.properties` by walking up parent directories. Keys are never
printed: logs show only the source and length, and all error text is scrubbed of key-like
strings. Models whose `keyName` has no key are reported as "no key" instead of failing.

## Budget cap

Every call reserves its estimated cost in `app/build/translation-bench/ledger.json` before it is
sent and books the actual cost afterwards (OpenAI: `usage.prompt_tokens` /
`completion_tokens` × the config prices, reasoning tokens included; OpenRouter: the billed
`usage.cost`). The cap applies to the **ledger total across all runs**: when
`spent + in-flight + next estimate > budget` the call is not made, the run stops scheduling
work and a partial report is written. Before each provider phase the harness estimates the
phase and drops up to `maxDropsPerPhase` of its most expensive models if the estimate would
exceed 90% of the cap. Delete `ledger.json` to reset the running total.

A default run (3 samples × 8 OpenAI + 8 OpenRouter models, judge on) measured about $0.60
(2026-10-11); the OpenAI phase alone is about $0.40. The judge panel adds about $0.25 per full run
(see [Rejudge](#rejudge)).

## Samples

Configured in `samples` (one ~3,000-char excerpt per language, trimmed at a paragraph boundary
between `sampleMinChars` and `sampleMaxChars`). Sources fetched at run time with OkHttp + Jsoup
and cached under `app/build/translation-bench/cache/`. The exact excerpt that was translated is
snapshotted in `cache/sample-<id>.json` and reused while its `origin` matches the configured
`url`, so report and rejudge runs grade the same text even if the site changes:

- `syosetu` (`.p-novel__body p`), `kakuyomu` (`.widget-episodeBody p`), `quanben`
  (`#contentbody`), `novelpia` (`POST /proc/viewer_data/<episode>` with the viewer Referer;
  cover and invisible watermark lines are removed). `url` may list several comma-separated
  pages (consecutive episodes) that are concatenated.
- `file`: `url` is a local path.

Your own chapters: drop UTF-8 text files named `bench/samples/<lang>-<name>.txt`
(`lang` = `zh`, `ja` or `ko`, one paragraph per line). They are added automatically
(`bench/samples/` is gitignored). If a configured site is unreachable, `bench/samples/<lang>.txt`
is used as a fallback and the report says so. Sample text and translations stay in the local
`build/` directory; do not commit or publish them.

## Models

Each entry in `models` either references one of the app's model presets
(`app/src/main/java/com/lagradost/quicknovel/translator/ModelPresets.kt`) or is fully explicit:

```json
{ "preset": "gpt-6-luna" }
{ "preset": "or-gemini-3.1-flash-lite", "priceNote": "OpenRouter default route, estimate" }
{ "preset": "gpt-6-sol", "id": "gpt-6-sol-low", "reasoningEffort": "low" }
{ "id": "gpt-5-mini", "inputPerM": 0.25, "outputPerM": 2.00, "reasoningEffort": "minimal" }
{ "id": "anthropic/claude-haiku-5.5", "inputPerM": 0.10, "outputPerM": 0.50,
  "baseUrl": "https://openrouter.ai/api/v1", "keyName": "OPENROUTER_API_KEY",
  "headers": { "HTTP-Referer": "...", "X-Title": "..." },
  "extraBody": { "reasoning": { "enabled": false }, "temperature": null },
  "priceNote": "OpenRouter default route, estimate" }
```

- `preset`: takes the API model id, base URL, `extraBody`, temperature behaviour
  (`sendTemperature: false` becomes `"temperature": null`), `reasoning_effort`, headers, key name
  (by provider: `OPENAI_API_KEY`, `OPENROUTER_API_KEY`, `GEMINI_API_KEY`, `DEEPSEEK_API_KEY`,
  `DASHSCOPE_API_KEY`, `ANTHROPIC_API_KEY`) and prices from the preset, so the bench measures
  exactly what the app sends. Any field given next to it overrides the preset (`extraBody`
  entries are merged, explicit ones win). `id` defaults to the preset's model id; `model`
  (the id sent to the API) defaults to `id`.
- Prices are USD per 1M tokens and drive estimates and OpenAI cost accounting.
- `reasoningEffort` (OpenAI): sent as `reasoning_effort`; if the API rejects it the harness
  steps down `none → minimal → low → omitted` (rejected requests cost nothing) and records the
  change under "Request-parameter adjustments". `temperature` is dropped whenever effort is not
  `none`, and `max_completion_tokens` is used instead of `max_tokens`.
- `extraBody` is passed to the engine's `extraBody` and merged into every request; a `null`
  value removes a field (`"temperature": null` for models that reject it, e.g. Kimi).
  OpenRouter's unified reasoning switch is `"reasoning": {"enabled": false}` or
  `{"effort": "low"}`; models with mandatory reasoning reject `enabled: false`.
- `maxTokensParam` overrides the output-limit field (`max_tokens` for non-OpenAI by default);
  `completionFactor`/`minCompletionTokens` size it (default 2.5 × prompt tokens).
- `sendTemperature: false` makes the engine never send `temperature`.
- `enabled: false` keeps a model in the config without running it (the direct Gemini, DeepSeek
  and Qwen presets are listed disabled; enable them when you have those keys).
- `promptProfile`: the app prompt profile to translate with (see below); unset = the preset's
  own profile, or the standard prompt for explicit models.
- Models are run in phases by provider (OpenAI first); the bible always uses `bibleModel`
  (OpenAI); judges are `judge.model` plus `judges.panel`.

## Prompt profiles

A prompt profile is a short list of extra system-prompt rules for one model, appended after the
seven standard rules and numbered 8, 9, … (optionally with extra rules for one source language).
Code: `PromptProfile.kt` (`PromptProfiles.ALL`) in
`app/src/main/java/com/lagradost/quicknovel/translator/`. Each `ModelPreset` names its profile
(`promptProfile`, default `default` = the standard prompt, byte-identical to the prompt before
profiles existed); the engine takes it from `EngineConfig`, and `TranslationOptions.promptProfile`
overrides it for the primary model only (a fallback model keeps its own). A custom model picks
its profile in the model dialog (default: standard).

| Profile | Rules | Used by |
|---|---|---|
| `default` | none | every preset except the two below; custom models unless changed |
| `general` | 7: no glossary tags after names, implied subjects, keep profanity strength, idioms/slang by meaning, author's notes and pen names, one word per rank/unit, keep scene breaks and one-line paragraphs | `gemini-3.1-flash-lite`, `or-gemini-3.1-flash-lite` |
| `luna` | `general` + scope of "rare/for once" adverbs + re-read for literal phrasing | tested on GPT-6 Luna, not adopted |
| `gemini` | `general` + swearing strength + exact paragraph count + Japanese topic subjects (ja only) | tested on Gemini Flash-Lite, not adopted |

Rule examples deliberately avoid the benchmark's own sentences, so a profile is not tuned to the
three samples.

### Testing a profile (A/B)

Add a variant as its own model entry with a distinct id (`<model>@<variant>`), so results,
judge scores and the report stay separate per variant:

```json
{ "preset": "gpt-6-luna", "id": "gpt-6-luna@general", "promptProfile": "general", "enabled": false },
{ "preset": "gpt-6-luna", "id": "gpt-6-luna@rerun", "promptProfile": "default", "enabled": false }
```

then run only the variants (judging happens inline with the full panel):

```sh
BENCH=1 ./gradlew :app:testDebugUnitTest --rerun --tests "com.lagradost.quicknovel.bench.TranslationBench" -Dbench=1 -Dbench.models=gpt-6-luna@rerun,gpt-6-luna@general --console=plain
```

and compare with `-Dbench.reportOnly=true -Dbench.models=gpt-6-luna,gpt-6-luna@rerun,gpt-6-luna@general`.

- The `@…` suffix is ignored for self-judging exclusion: `gpt-6-luna` never judges
  `gpt-6-luna@general`.
- Always include a `@rerun` entry (same model, `default` profile, fresh run). One output per
  sample is noisy: in the 2026-10-11 A/B three standard-prompt runs of GPT-6 Luna scored 8.26,
  8.70 and 8.96 on the same three chapters. A profile that moves the panel less than that
  spread is a tie; look at deterministic metrics (paragraph fidelity, separators, tags) and the
  targeted passages instead.
- A replicate needs another id (`@general-r2`), because the result store keeps one output per
  (sample, model id).
- A preset reference inherits the preset's profile; the stored Gemini baseline pins
  `"promptProfile": "default"` so its stored results stay labelled correctly.
- Cost: a Luna variant is about $0.022 for three chapters with the full panel, a Gemini variant
  about $0.031 (gpt-5.4-mini judging is two thirds of it). The 2026-10-11 A/B (12 variant runs)
  cost $0.317.

## Story bible

For each sample one call to `bibleModel` extracts characters (English rendering, gender, role)
and places / techniques / ranks as JSON. It is cached in `cache/bible-<sample>.json`, so every
model, and every later run, gets the same glossary. `BenchEngineAdapter` calls the story-bible
engine exactly like the app: `translateChapter(..., glossaryTerms, context = StoryContext(premise),
options = TranslationOptions(strictMode, carryStoryContext))`, with each bible entry mapped to a
`GlossaryTerm` (gender for characters, role as notes). `strictMode` and `carryStoryContext` are
top-level config switches (default on, as in the app). The engine's content-filter fallback is
not used in the bench (each model is measured on its own).

## Results

`app/build/translation-bench/<timestamp>/`:

- `report.md`: leaderboard, per-model spend, parameter adjustments, per-sample excerpts
  (first 600 chars of each output under the source), glossary misses / leaks / gender flags /
  refusals, total spend for the run and the ledger.
- `report.html`: the same with full outputs side by side (source | each model) and metric chips.
- `raw/<model>/<sample>.txt`, `bibles/`, `samples/`, `metrics.json` (includes per-judge scores,
  biases, Spearman pairs and exclusions).

Shared across runs: `results/` (latest translation per sample/model), `judges/` (panel scores),
`manual-judges/`, `cache/` (pages, sample snapshots, bibles), `ledger.json`.

### Reading the leaderboard

- **Panel adj. / Panel raw / v1-only / per-judge**: see [Judge panel](#judge-panel). Cheap
  judges are noisy and differ a lot in leniency (on the 2026-10-11 run gemini-3.1-flash-lite gave
  9.5 on average, gpt-5.4-mini 6.5); treat differences under ~0.5 as ties and read the outputs.
- **Adherence %**: bible terms present in the sample whose English rendering appears in the
  output. A correct but different rendering counts as a miss; check the misses list.
- **CJK leaks**: untranslated Han/kana/Hangul characters in the output.
- **Para fidelity**: output paragraphs / source paragraphs; well below 1 means merged or
  dropped paragraphs.
- **Gender mism.**: opposite-gender pronouns in sentences that name exactly one bible
  character. Crude (the pronoun can refer to someone else); compare models relative to each
  other.
- **Refusals**: outputs containing a refusal (some models decline to translate well-known
  published novels chunk by chunk); a refusal makes the model unusable for that title.
- **$/chapter, $/100 ch, $/1,000 ch**: measured cost scaled to a `chapterChars` (4,500)
  source-character chapter, including the system prompt and glossary actually sent;
  $/100 also adds `bibleCallsPer100Chapters` bible pre-passes. **Meets** compares $/100 with
  `targetUsdPer100Chapters` (YES) and `stretchUsdPer100Chapters` (stretch).
- **Errors**: HTTP errors, `finish_reason` other than `stop`, empty output, refusals.
