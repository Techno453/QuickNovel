# Choosing a model for CJK → English novel translation

Compiled 2026-10-11 from official pricing/docs pages, published benchmarks and
community reports. Prices are USD per 1M tokens and change often; re-check before
relying on them. Items marked *unverified* could not be confirmed on an official page.
The in-repo benchmark (see `translation-bench.md`) is the final word on quality.

## Cost model used below

Per chapter: 4,500 source characters, about 1,000 tokens of prompt overhead
(system prompt + story bible), about 4,000 English output tokens, reasoning /
thinking turned off or at its lowest setting. Input tokens depend on the tokenizer:

| Tokenizer family | tokens per zh char | ja | ko | Source |
|---|---|---|---|---|
| OpenAI o200k (GPT-4o … GPT-6) | 0.97 | 0.89 | 0.86 | measured |
| Anthropic Claude | ~1.15–1.3 | ~0.95–1.05 | ~1.05–1.1 | third-party measurements |
| Google Gemini (Gemma 3 proxy) | 0.79 | 0.68 | 0.82 | measured, proxy |
| DeepSeek V3/V4 | 0.63 | 0.80 | 0.99 | measured; docs say 0.6 for zh |
| Qwen 3.x | 0.69 | 0.77 | 1.00 | measured |
| Zhipu GLM | 0.68 | 0.83 | 1.16 | measured |
| Moonshot Kimi | 0.60 | 0.91 | 1.20 | measured |

So a Chinese chapter costs about 5,400 input tokens on OpenAI, 6,400 on Claude and
3,800 on DeepSeek. Output dominates cost for every model.

The app's estimate (`CostEstimator`, shown in the model picker and the Translate dialog) uses
the same model: input = chars × tokens-per-char of the model's tokenizer + 1,000 + 120 per
Story Bible term (at most 2,000); output = 0.9 × chars × the o200k ratio (about 4,000 tokens
for 4,500 Chinese characters); × 1.15 with strict consistency mode on. It runs 25–50% above
what the benchmark measured, so treat it as an upper bound.

User target: **at most $3–5 per 100 chapters** (≤ $0.03–0.05 per chapter).

## Cost per chapter (Chinese source), cheapest first

| Model | Provider | $/1M in | $/1M out | $/chapter | $/100 ch | Fits target | Notes |
|---|---|---|---|---|---|---|---|
| glm-4.7-flash, glm-4.5-flash | Z.ai | 0 | 0 | 0 | 0 | yes | free tier, limits undocumented |
| qwen-flash (legacy) | Alibaba | 0.05 | 0.40 | 0.0018 | 0.18 | yes | thinking off by default |
| qwen3.8-flash | Alibaba | 0.15 | 0.47 | 0.0025 | 0.25 | yes | thinking ON by default, disable it |
| gpt-6-luna | OpenAI | 0.10 | 0.50 | 0.0025 | 0.25 | yes | reasoning_effort "none" |
| claude-haiku-5-5 | Anthropic | 0.10 | 0.50 | 0.0026 | 0.26 | yes | thinking can be disabled; price for prompts ≤100K |
| glm-5.3-flash | Z.ai | 0.15 | 0.50 | 0.0026+ | 0.26+ | yes | always thinks, so more output |
| deepseek-flash (V4.1) off-peak / peak | DeepSeek | 0.15 / 0.30 | 0.60 / 1.20 | 0.0030 / 0.0060 | 0.30 / 0.60 | yes | disable thinking; peak = 01–04 and 06–10 UTC weekdays |
| gpt-4o-mini | OpenAI | 0.15 | 0.60 | 0.0032 | 0.32 | yes | legacy baseline |
| mistral-small-2603 | Mistral | 0.15 | 0.60 | 0.0032 | 0.32 | yes | tokenizer unverified |
| gemini-3.1-flash-lite | Google | 0.25 | 1.50 | 0.0071 | 0.71 | yes | |
| qwen3.7-plus | Alibaba | 0.40 | 1.60 | 0.0080 | 0.80 | yes | |
| gpt-5-mini | OpenAI | 0.25 | 2.00 | 0.0093 | 0.93 | yes | |
| deepseek-v4-pro off-peak / peak | DeepSeek | 0.66 / 1.32 | 1.98 / 3.96 | 0.0105 / 0.021 | 1.05 / 2.09 | yes | |
| gemini-3.5-flash-lite | Google | 0.30 | 2.50 | 0.0114 | 1.14 | yes | |
| mistral-large-4 (sale price) | Mistral | 0.68 | 2.09 | 0.012 | 1.2 | yes | list price is double |
| grok-4.20 non-reasoning | xAI | 1.25 | 2.50 | 0.017 | 1.7 | yes | |
| gemini-3.8-flash | Google | 0.75 | 3.75 | 0.018 | 1.8 | yes | price doubles 2027-01-01 |
| kimi-k2.6 | Moonshot | 0.95 | 4.00 | 0.020 | 2.0 | yes | temperature must be exactly 0.6 or 1.0 |
| glm-5.3 | Z.ai | 1.40 | 4.40 | 0.023+ | 2.3+ | yes | thinking cannot be disabled |
| qwen3.8-max | Alibaba | 2.00 | 6.00 | 0.032 | 3.2 | yes | |
| gpt-6-sol | OpenAI | 2.00 | 10.00 | 0.051 | 5.1 | edge | reasoning "none" |
| gpt-6.1-sol | OpenAI | 2.00 | 10.00 | 0.051+ | 5.1+ | edge | lowest effort is "low" |
| claude-sonnet-5-5 | Anthropic | 2.00 | 10.00 | 0.053 | 5.3 | edge | thinking cannot be fully disabled |
| gpt-4o | OpenAI | 2.50 | 10.00 | 0.053 | 5.3 | edge | legacy |
| gemini-3.1-pro-preview | Google | 2.00 | 12.00 | 0.057+ | 5.7+ | edge | thinking defaults to high |
| gpt-5.6-terra | OpenAI | 2.00 | 12.00 | 0.059 | 5.9 | no | previous generation |
| kimi-k3 | Moonshot | 3.00 | 15.00 | 0.071+ | 7+ | no | thinking always on |
| claude-opus-5-5 | Anthropic | 4.00 | 20.00 | 0.106+ | 11+ | no | |
| gpt-6-astra | OpenAI | 10.00 | 50.00 | 0.25 | 25 | no | |

OpenRouter (one key for all of the above) adds a 5.5% fee when buying credits and
no per-token markup, but its default route for a model can be pricier than the
provider's own endpoint (DeepSeek Flash is 0.30/1.20 on OpenRouter versus 0.15/0.60
direct off-peak).

## Quality evidence

Measured benchmarks. None covers the exact zh/ja/ko → en web-novel task with 2026 models.

- **zh→en web novels (DITING, Oct 2025, 14 models):** DeepSeek-V3 5.16 > GPT-4o 5.09 >
  Qwen3-32B 4.56 ≈ Google Translate > Qwen3-14B 4.38. Chinese-trained models beat larger
  foreign ones. Claude and Gemini were not tested.
- **ja→en visual novels (VNTL leaderboard, to Jan 2025):** GPT-4o 0.752, Claude 3 Opus 0.746,
  Claude 3.5 Sonnet 0.744, DeepSeek-V3 0.742. Effectively tied.
- **ja→en (Shisa JP-TL-Bench, Jan 2026, Gemini-judged):** Gemini 2.5 Pro 96%, Gemini 2.5 Flash
  93%, GPT-4o 76%. Judge bias toward Gemini is likely.
- **WMT24 general MT:** Claude 3.5 Sonnet best overall. **WMT25:** Gemini 2.5 Pro best overall.
  en→zh: Claude-4 86.9 > DeepSeek-V3 85.0 > Qwen3-235B 84.0 = GPT-4.1 84.0 > Gemini-2.5-Pro 83.8.
- **Korean:** no web-novel benchmark exists. LLM judges fail to notice honorific errors, and
  translation-tuned models over-use polite forms.

Community consensus: MTL tooling for Chinese (LinguaGacha, AiNiee) defaults to DeepSeek
with Gemini second. Translators agree that a glossary pre-pass plus consistent
terminology matters more than model choice. No model has been publicly benchmarked on
Korean web novels.

## Failure modes to design around

| Provider | Behaviour | App handling |
|---|---|---|
| Gemini 3+ | PROHIBITED_CONTENT blocks cannot be disabled; output silently truncated; 18+ chapters fail | check finish_reason and length ratio; do not use as the only model for mature novels |
| DeepSeek | HTTP 400 "Content Exists Risk" on politically sensitive text, retry never succeeds; stray CJK characters in output | non-retryable, fall back to another model; CJK-leak check |
| Qwen | data_inspection_failed on input or output | non-retryable, fall back |
| Kimi | content_filter 400; temperature must equal its fixed value | do not send temperature |
| GLM | error 1301 or finish_reason "sensitive"; temperature 0–1 only | treat as a block |
| OpenAI, Claude | soften explicit sex and violence; Claude 4.7+ rejects temperature < 1 and top_p ≠ 0.99 through the OpenAI-compatible layer; Claude Haiku 5.5 refuses some chapters outright (see below) | omit temperature; detect summarised output |
| All | omissions grow with chunk size; glossary entries sometimes ignored; paragraphs skipped | small chunks, adherence check, paragraph-count check, strict retry |

The app implements the "fall back to another model" column: with a **Fallback model** set
(Settings → AI Translation, off by default), a passage is re-translated once with the fallback
when the provider's content filter blocks it (DeepSeek "Content Exists Risk", Qwen
`data_inspection_failed`, Kimi `content_filter`, Z.ai 1301 / `finish_reason: "sensitive"`,
Gemini prohibited content or any finish reason other than stop/length, OpenAI
`content_filter`), or when the output looks like a refusal (fewer than half the source
paragraphs and "I can't", "I cannot", "unable to", "copyright" or "I won't" in the first 300
characters). Chapters that used it are counted in the completion summary and flagged in the
database (`used_fallback`, with the model(s) actually used).

## OpenAI-compatible base URLs

| Provider | Base URL | Caveats |
|---|---|---|
| OpenAI | https://api.openai.com/v1 | reasoning models need max_completion_tokens; omit temperature unless effort is "none" |
| Anthropic | https://api.anthropic.com/v1/ | compatibility layer is "not production-ready"; no prompt caching; thinking on by default |
| Google | https://generativelanguage.googleapis.com/v1beta/openai/ | reasoning_effort maps to thinking level; cannot be off on Gemini 3 |
| DeepSeek | https://api.deepseek.com | thinking on by default; send reasoning_effort "none" or thinking type "disabled" |
| Alibaba Qwen | https://dashscope-intl.aliyuncs.com/compatible-mode/v1 (international, used by the app preset); dashscope-us or region-specific hosts otherwise | keys are region-locked; enable_thinking via extra body |
| Moonshot Kimi | https://api.moonshot.ai/v1 | fixed temperature; thinking type "disabled" on k2.6 |
| Z.ai GLM | https://api.z.ai/api/paas/v4 | temperature 0–1 |
| xAI | https://api.x.ai/v1 | reasoning models reject penalty and stop parameters |
| Mistral | https://api.mistral.ai/v1 | random_seed instead of seed; keep safe_prompt false |
| OpenRouter | https://openrouter.ai/api/v1 | model IDs are org/model; unified "reasoning" parameter; unsupported params silently ignored |

## Benchmark results (2026-10-11)

In-repo benchmark (`translation-bench.md`): 16 models, one ~3,000-character chapter each of
Chinese (斗破苍穹), Japanese (無職転生) and Korean (빙의가 체질), the app's real engine with a
Story Bible, reasoning off or lowest. Cost is measured, scaled to 100 chapters of 4,500
characters (bible pre-pass included).

| Model (reasoning) | Judge | Adherence % | Para fidelity | Refusals | Latency s | $/100 ch |
|---|---|---|---|---|---|---|
| gpt-6-sol (none) | 8.44 | 100 | 1.00 | 0 | 20.6 | 3.73 |
| gpt-6-luna (none) | 8.11 | 100 | 1.00 | 0 | 16.8 | 0.19 |
| gpt-4o | 8.11 | 97 | 0.99 | 0 | 11.5 | 3.82 |
| gemini-3.1-flash-lite (thinking off) | 8.00 | 100 | 0.98 | 0 | 7.9 | 0.57 |
| gpt-6.1-sol (low) | 8.00 | 100 | 1.00 | 0 | 35.4 | 3.86 |
| gpt-5.6-terra (none) | 8.00 | 100 | 1.00 | 0 | 20.0 | 4.47 |
| gpt-5.4-mini (none) | 7.89 | 100 | 1.00 | 0 | 11.6 | 1.69 |
| qwen3.8-flash (off) | 7.78 | 100 | 1.00 | 0 | 38.8 | 0.19 |
| kimi-k2.6 (off) | 7.67 | 97 | 0.96 | 0 | 46.2 | 1.25 |
| deepseek-v4.1-flash (off) | 7.56 | 100 | 1.00 | 0 | 12.6 | 0.26 |
| mistral-small-2603 | 7.11 | 91 | 0.86 | 0 | 18.9 | 0.18 |
| gpt-4o-mini | 7.11 | 97 | 0.97 | 0 | 13.6 | 0.23 |
| glm-5.3-flash (low) | 7.11 | 97 | 1.00 | 0 | 31.8 | 0.32 |
| claude-sonnet-5.5 (low) | 7.00 | 97 | 1.00 | 0 | 23.5 | 5.81 |
| gpt-5-mini (minimal) | 6.33 | 100 | 0.99 | 0 | 10.4 | 0.64 |
| claude-haiku-5.5 (off) | 4.56 | 91 | 0.67 | 2 | 13.4 | 0.22 |

Non-OpenAI models ran through OpenRouter's default route.

- **Claude Haiku 5.5 refused** the Chinese and Korean samples, citing copyright, and abridged
  what it did return (paragraph fidelity 0.67). It is not offered as a preset.
- **Judge bias:** the judge was gpt-6-luna, a small OpenAI model scoring its own family and
  larger models. Treat judge differences under ~0.5 as ties and lean on the objective columns
  (adherence, leaks, paragraph fidelity, refusals) and on reading the outputs.
- Every model except Haiku, Mistral Small and Kimi kept ≥ 0.98 paragraph fidelity; glossary
  adherence was 97–100% for all but Mistral Small and Haiku (91%). With the Story Bible in the
  prompt, terminology is no longer what separates models; fluency and price are.

## Recommendations

| Use | Model (app preset) | ≈ $/100 ch measured | Notes |
|---|---|---|---|
| **Default** | gpt-6-luna, reasoning none | 0.19 | Second-best judge score, 100% adherence, cheapest OpenAI model. Default for new installs. |
| **Quality** | gpt-6-sol, reasoning none | 3.73 | Best judge score; inside the $3–5 target. |
| Fast / cheap alternative | gemini-3.1-flash-lite, lowest thinking | 0.57 | Fastest (7.9 s/chapter), judge 8.0. Gemini's PROHIBITED_CONTENT block cannot be disabled, so mature novels may fail; keep a fallback. |
| Cheapest, Chinese-trained | deepseek-flash (thinking disabled) or qwen3.8-flash (enable_thinking false) | 0.19–0.26 | DeepSeek is half price off-peak but rejects politically sensitive text; Qwen is slow (39 s/chapter) and its keys are region-locked. |
| Fallback | gemini-3.1-flash-lite (cheap) or gpt-6-sol (quality) | — | Off by default. Pick a model from a different provider than the main one, so the same filter does not block it twice. |
| Not recommended | gpt-5.4-mini, gpt-4o, gpt-4o-mini, claude-sonnet-5.5, claude-haiku-5.5 | | 5.4-mini costs 9× Luna for a lower score; 4o costs 20× Luna for the same score; 4o-mini scores lower; Sonnet is over budget and scored 7.0; Haiku refuses. |

Each preset is also available through OpenRouter (one key for all providers, with
`reasoning: {enabled: false}`), except Claude Sonnet, which is offered direct only. Local and
free: Qwen3-32B roughly matches Google Translate on Chinese and 14B is usable, but it needs a PC
with 16–24 GB of VRAM serving an OpenAI-compatible endpoint (Ollama or LM Studio); use the
**Custom** model option.

## Unverified items

Mistral "-latest" alias targets and some native IDs; xAI fast/cheap models and aliases;
OpenRouter content moderation; the Qwen tokenizer ratio (docs claim 1.5–1.8 chars per token);
the Gemini tokenizer (Gemma 3 used as proxy); exact context window of gpt-6-sol; whether
"minimal" reasoning fully disables thinking on Gemini 3.6; the retirement date of Gemini 2.5
Flash-Lite. Third-party price sites disagreed with the official pages and were not used.
