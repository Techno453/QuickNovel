# QuickNovel

**English** | [Русский](README_RU.md)

Adfree FOSS Android app for downloading novels. It also functions as an Epub reader.

**Discord:** https://discord.gg/5Hus6fM

**Download:** https://github.com/LagradOst/QuickNovel/releases

**Providers used:** 

- https://allnovel.org
- https://annas-archive.org
- https://bestlightnovel.com
- https://fenrirealm.com
- https://free.kolnovel.com
- https://freewebnovel.com
- https://fucknovelpia.com
- https://graycity.net
- https://indowebnovel.id
- https://kakuyomu.jp (Japanese)
- https://libread.com
- https://lightnovelstranslations.com
- https://lightnovelworld.org
- https://novelbin.com
- https://novelfire.net
- https://novelfull.com
- https://novelpia.com (Korean, free episodes only)
- https://novelsonline.org
- https://novlove.com
- https://readfrom.net
- https://readnovelfull.com
- https://rewayat.club
- https://risenovel.com
- https://www.royalroad.com
- https://sakuranovel.id
- https://www.scribblehub.com
- https://www.skynovels.net
- https://www.69shuba.com (Chinese)

**AI translation:**

Novels from Chinese, Japanese and Korean sources (69shuba, Kakuyomu, Novelpia, …) can be
translated into English with an LLM:

- **Story Bible** per novel: characters (with gender, role and speech style), places,
  sects, ranks, techniques and items, plus a premise and translation style (name romanization,
  honorifics). It is sent with every chapter so names stay consistent; new names found while
  translating are added automatically or queued for review. *Analyze novel* (long-press
  **Translate** on the novel page, or the Glossary menu) builds it from the first chapters.
- **Batch** translation of a chapter range from the novel page (runs in the background), or
  **on demand** when an untranslated chapter is opened in the reader. Translated chapters are
  stored on the device and marked "(AI translated)" in the reader.
- **Translated search**: search queries are translated into each provider's language, and titles
  and synopses can be shown in English (Google Translate).

It needs your own API key. Under **Settings → AI Translation** pick a model preset (default
GPT-6 Luna, about $0.20–0.30 per 100 chapters; GPT-6 Sol for the best quality; Gemini, DeepSeek,
Qwen and OpenRouter options) or any OpenAI-compatible endpoint (a local server, …), plus an
optional fallback model for passages a provider's content filter blocks. Chapter text is only
sent to those endpoints. See
[docs/translation-models.md](docs/translation-models.md) for model choice and cost, and
[docs/translation-bench.md](docs/translation-bench.md) for the translation quality benchmark.

**Screenshoots:**

<img src="./.github/home.jpg" height="400"/><img src="./.github/search.jpg" height="400"/><img src="./.github/downloads.jpg" height="400"/><img src="./.github/result.jpg" height="400"/><img src="./.github/reader.jpg" height="400"/>

**Legal notice:**

Any legal issues regarding the content on this application should be taken up with the actual file hosts and providers themselves as we are not affiliated with them.

In case of copyright infringement, please directly contact the responsible parties or the streaming websites.

The app is purely for educational and personal use.

QuickNovel does not host any content on the app, and has no control over what media is put up or taken down. QuickNovel functions like any other search engine, such as Google. QuickNovel does not host, upload or manage any videos, films or content. It simply crawls, aggregates and displayes links in a convenient, user-friendly interface.

It merely scrapes 3rd-party websites that are publicly accessable via any regular web browser. It is the responsibility of user to avoid any actions that might violate the laws governing his/her locality. Use QuickNovel at your own risk.

