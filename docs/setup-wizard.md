# First-run setup wizard

A short Compose wizard (`ui/setup/`) that collects what AI translation needs. It opens once on
first launch and any time from **Settings → AI Translation → Set up translation…**.

## Pages

1. **Welcome + languages**: what the app does; multi-select of Chinese / Japanese / Korean.
   Stored in `setup_reading_languages` (default preferences) and applied to the existing
   *Provider Languages* setting (`provider_lang_key`): unpicked CJK languages are turned off,
   every other language (English, …) keeps its state. Picking none changes nothing.
2. **Provider and key**: OpenAI (recommended), OpenRouter, Google Gemini or Later. Password
   field with Show/Hide and Paste; whitespace is stripped as you type or paste. A link opens
   the provider's key page. The key is stored with `SecurePrefs.setApiKey(provider, key)`, as
   the settings screen does. If the chosen provider differs from the selected model's provider,
   the provider's default model is selected so the new key is the one used.
   "Later" skips the test and model pages.
3. **Test connection**: one free GET, run automatically on entering the page
   (`translator/ConnectionTest.kt`, through `TranslationEngine.defaultClient`, 15 s timeout):

   | Provider | Request | Why |
   |---|---|---|
   | OpenAI | `GET https://api.openai.com/v1/models` | lists models, free |
   | Google Gemini | `GET https://generativelanguage.googleapis.com/v1beta/openai/models` | OpenAI-compatible model list, free |
   | OpenRouter | `GET https://openrouter.ai/api/v1/key` | its `/models` is public and would accept any key |

   Result mapping: 2xx JSON → OK; 429 → OK (rate limited, key accepted); 401/403 or a body
   saying the key is invalid (Gemini answers 400 `API_KEY_INVALID`) → invalid key; a region
   refusal (`unsupported_country_region_territory`, "User location is not supported"), HTTP 451,
   an HTML page instead of JSON (captive portal, CDN/firewall) or a TLS failure → blocked;
   no answer (DNS, refused, timeout) → network error; anything else (5xx, 404) → provider error.
   No completion is ever sent, so the test costs nothing.
4. **Model**: options filtered by provider: OpenAI → GPT-6 Luna (Default) and GPT-6 Sol
   (Quality); OpenRouter → the same two plus Gemini 3.1 Flash-Lite; Google → Gemini 3.1
   Flash-Lite ("Cheapest with Google"). Each shows the `CostEstimator.estimateForPreset`
   estimate for 100 chapters (preset prices, first language the user reads, current strict-mode
   setting) and the measured cost from the benchmark (0.19 / 3.73 / 0.57 USD, from the
   Recommendations table in [translation-models.md](translation-models.md)).
5. **Done**: summary (languages, provider, masked key, connection result, model and cost),
   toggles for the two features that run on their own — read-ahead (`read_ahead_chapters`,
   default 2; off = 0) and the library new-chapter check (`library_update_frequency`, daily or
   off; asks for the notification permission on Android 13+) — plus links to the Spending
   screen and to the full settings.

Skip is on every page except Done; system Back goes to the previous page, and Back on the first
page counts as Skip. Values are saved when leaving a page with Next, so Skip keeps what was
already confirmed.

## First-run detection

`SetupChoices.shouldShowOnLaunch(setupCompleted, hasAnyApiKey)`:

- `setup_completed` (boolean in `SecurePrefs`) is set by Finish, Skip and Back-on-first-page;
- `SecurePrefs.hasAnyApiKey()` is true when any provider has a key, so existing users who
  update with a key configured never see the wizard (existing users without a key see it once).

MainActivity checks this off the main thread right after warming up `SecurePrefs`, only when
`savedInstanceState == null`, at most once per process. The flag lives in the encrypted
preferences, which are excluded from backups: after a restore (keys must be re-entered) the
wizard appears again.

## Key storage fallback warning

`SecurePrefs` opens `EncryptedSharedPreferences` and silently falls back to plain
`SharedPreferences` (`translator_prefs_fallback.xml`) when the keystore is broken. The choice is
made by the Android-free `KeyStoreChoice.open` (unit tested) and exposed as
`SecurePrefs.isUsingPlainFallback(context)`. When true, the settings screen (under the API key)
and the wizard's key page show: "Secure storage is unavailable on this device; the key is stored
unencrypted."

## Files

- `ui/setup/SetupActivity.kt` — host activity (edge to edge, app theme), not exported
- `ui/setup/SetupWizardScreen.kt` — the pages; content column capped at
  `AdaptiveLayout.MAX_SHEET_WIDTH_DP` (640 dp) with `centeredGutter`, fixed bottom bar
- `ui/setup/SetupChoices.kt` — pure decisions (steps, model options, costs, languages), tested
- `ui/setup/SetupWizard.kt` — first-run launch, reading languages, open-settings intent
- `translator/ConnectionTest.kt` — the free connection test, tested against a local fake server
- `res/values/strings_setup.xml`
