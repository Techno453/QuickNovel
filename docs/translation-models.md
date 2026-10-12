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
| OpenAI, Claude | soften explicit sex and violence; Claude 4.7+ rejects temperature < 1 and top_p ≠ 0.99 through the OpenAI-compatible layer | omit temperature; detect summarised output |
| All | omissions grow with chunk size; glossary entries sometimes ignored; paragraphs skipped | small chunks, adherence check, paragraph-count check, strict retry |

## OpenAI-compatible base URLs

| Provider | Base URL | Caveats |
|---|---|---|
| OpenAI | https://api.openai.com/v1 | reasoning models need max_completion_tokens; omit temperature unless effort is "none" |
| Anthropic | https://api.anthropic.com/v1/ | compatibility layer is "not production-ready"; no prompt caching; thinking on by default |
| Google | https://generativelanguage.googleapis.com/v1beta/openai/ | reasoning_effort maps to thinking level; cannot be off on Gemini 3 |
| DeepSeek | https://api.deepseek.com | thinking on by default; send reasoning_effort "none" or thinking type "disabled" |
| Alibaba Qwen | https://dashscope-us.aliyuncs.com/compatible-mode/v1 (US) or region-specific | keys are region-locked; enable_thinking via extra body |
| Moonshot Kimi | https://api.moonshot.ai/v1 | fixed temperature; thinking type "disabled" on k2.6 |
| Z.ai GLM | https://api.z.ai/api/paas/v4 | temperature 0–1 |
| xAI | https://api.x.ai/v1 | reasoning models reject penalty and stop parameters |
| Mistral | https://api.mistral.ai/v1 | random_seed instead of seed; keep safe_prompt false |
| OpenRouter | https://openrouter.ai/api/v1 | model IDs are org/model; unified "reasoning" parameter; unsupported params silently ignored |

## Recommendations, pending the benchmark

- **Cheapest acceptable, Chinese:** DeepSeek Flash with thinking off (strongest zh evidence
  among cheap models, about $0.30–0.60 per 100 chapters) or Qwen 3.8 Flash. Both can refuse
  politically sensitive text, so keep a fallback model configured.
- **Cheapest acceptable, Japanese and Korean:** Gemini 3.1 Flash-Lite (about $0.71) if the
  content is tame; otherwise gpt-6-luna, Claude Haiku 5.5 or gpt-5-mini ($0.25–0.93). None of
  these has a published CJK literary benchmark yet.
- **Balanced:** gpt-5-mini, DeepSeek V4 Pro off-peak, or Qwen 3.7 Plus ($0.8–1.1 per 100).
- **Best quality at the edge of the target:** gpt-6-sol with reasoning off, or Claude Sonnet 5.5
  ($5.1–5.3 per 100). Gemini 3.1 Pro for Japanese only if moderation is not a problem.
- **Local and free:** Qwen3-32B roughly matches Google Translate on Chinese and 14B is usable,
  but it needs a PC with 16–24 GB of VRAM serving an OpenAI-compatible endpoint (Ollama or
  LM Studio). Not realistic on the phone itself.

## Unverified items

Mistral "-latest" alias targets and some native IDs; xAI fast/cheap models and aliases;
OpenRouter content moderation; the Qwen tokenizer ratio (docs claim 1.5–1.8 chars per token);
the Gemini tokenizer (Gemma 3 used as proxy); exact context window of gpt-6-sol; whether
"minimal" reasoning fully disables thinking on Gemini 3.6; the retirement date of Gemini 2.5
Flash-Lite. Third-party price sites disagreed with the official pages and were not used.
