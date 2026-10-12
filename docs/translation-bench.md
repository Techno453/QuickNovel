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
| `bench.dryRun` | `-Dbench.dryRun=true` | fetch samples, fake outputs, compute metrics, write the report; **no API calls** |
| `bench.judge` | `-Dbench.judge=false` | skip the LLM judge |
| `bench.reportOnly` | `-Dbench.reportOnly=true` | no API calls: rebuild the report from stored results (metrics recomputed) |
| `bench.budget` / `BENCH_BUDGET_USD` | `BENCH_BUDGET_USD=1.50` | cost cap (default 2.50) |
| `bench.parallelism` | `-Dbench.parallelism=2` | concurrent translations (default 4) |

Re-running a subset (e.g. one model after fixing its parameters) merges into the report: the
latest result per (sample, model) is kept in `app/build/translation-bench/results/` and every
report is built from those, so never re-run everything to fix one model.

## API keys

Put keys in `bench.local.properties` at the project root (gitignored via `*.local.properties`):

```
OPENAI_API_KEY=sk-...
OPENROUTER_API_KEY=sk-or-...
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
(2026-10-11); the OpenAI phase alone is about $0.40.

## Samples

Configured in `samples` (one ~3,000-char excerpt per language, trimmed at a paragraph boundary
between `sampleMinChars` and `sampleMaxChars`). Sources fetched at run time with OkHttp + Jsoup
and cached under `app/build/translation-bench/cache/`:

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

Each entry in `models`:

```json
{ "id": "gpt-5-mini", "inputPerM": 0.25, "outputPerM": 2.00, "reasoningEffort": "minimal" }
{ "id": "anthropic/claude-haiku-5.5", "inputPerM": 0.10, "outputPerM": 0.50,
  "baseUrl": "https://openrouter.ai/api/v1", "keyName": "OPENROUTER_API_KEY",
  "headers": { "HTTP-Referer": "...", "X-Title": "..." },
  "extraBody": { "reasoning": { "enabled": false }, "temperature": null },
  "priceNote": "OpenRouter default route, estimate" }
```

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
- `enabled: false` keeps a model in the config without running it.
- Models are run in phases by provider (OpenAI first); the bible and the judge always use
  `bibleModel` / `judge.model` (OpenAI).

## Story bible

For each sample one call to `bibleModel` extracts characters (English rendering, gender, role)
and places / techniques / ranks as JSON. It is cached in `cache/bible-<sample>.json`, so every
model, and every later run, gets the same glossary. `BenchEngineAdapter` converts it to the
engine's `List<GlossaryTerm>` (gender and role go into the description). When the engine's
story-bible API lands, only `BenchEngineAdapter.kt` needs to change.

## Results

`app/build/translation-bench/<timestamp>/`:

- `report.md`: leaderboard, per-model spend, parameter adjustments, per-sample excerpts
  (first 600 chars of each output under the source), glossary misses / leaks / gender flags /
  refusals, total spend for the run and the ledger.
- `report.html`: the same with full outputs side by side (source | each model) and metric chips.
- `raw/<model>/<sample>.txt`, `bibles/`, `samples/`, `metrics.json`.

### Reading the leaderboard

- **Judge**: `judge.model` scores accuracy, fluency and terminology 1–10 (average shown). A
  small model judging larger ones is noisy; treat differences under ~0.5 as ties and read the
  outputs.
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
