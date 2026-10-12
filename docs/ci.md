# CI, releases and provider tests

## Workflows (`.github/workflows/`)

| Workflow | File | Runs on | Does |
|---|---|---|---|
| **CI** | `ci.yml` | push to `master`, every pull request, manual | `./gradlew :app:testDebugUnitTest :app:assembleDebug`; uploads the debug APK (`QuickNovel-debug-<sha>`) and the unit-test report (`unit-test-report`) as artifacts, kept 14 days |
| **Nightly live provider check** | `nightly-live.yml` | daily 03:17 UTC, manual (optional provider filter) | runs `CjkProvidersLiveSmokeTest` with `-Dlive=1`: one search, one novel page and one chapter per CJK provider. A broken provider fails the job. The job summary lists each provider's result; the report is uploaded as `live-test-report` |
| **Release** | `release.yml` | push of a tag `v*`, manual (with a tag) | checks the tag is `v<versionName>`, runs the unit tests, builds the release APKs (R8-shrunk, one per ABI plus a universal one; signed with the release key if the secrets exist, otherwise with a throwaway debug key and a warning in the notes), publishes a GitHub release titled `QuickNovel <versionName>` with `QuickNovel-<versionName>-{arm64-v8a,armeabi-v7a,x86_64,universal}.apk` and `QuickNovel-<versionName>-mapping.txt.gz` attached (see [Release APKs](#release-apks-shrinking-and-crash-traces)) |
| **Pre-release** | `pre-release.yml` | after a green CI run of a push to `master`, manual | rebuilds the debug APK of that commit and replaces the rolling `pre-release` GitHub release (marked pre-release) with `QuickNovel-<versionName>-pre.<run>.apk` |

All jobs use Ubuntu, Temurin JDK 21 (`gradle/gradle-daemon-jvm.properties` asks for a JDK 21
Gradle daemon; AGP 9 needs at least 17; the app compiles to Java 11 bytecode),
`android-actions/setup-android` for the SDK (`ANDROID_HOME`, licences) plus
`platforms;android-37.0`, and `gradle/actions/setup-gradle` for caching.

The build needs no local files on CI: there is no `local.properties` (the SDK comes from
`ANDROID_HOME`), signing comes from environment variables, and `bench.local.properties` is only
read by the translation bench test at run time, which is skipped unless `-Dbench=1` / `BENCH=1`.

**Failure emails.** GitHub emails failed runs of a *scheduled* workflow to the user who last
changed its `cron` line (with the default notification settings: *Settings → Notifications →
Actions*). GitHub disables scheduled workflows after 60 days without repository activity; re-enable
the nightly check in the Actions tab if that happens.

## Signing secrets

**Status (2026-10-12):** the four secrets are set. Releases 3.9.0 and 3.9.1 were published before
that, each signed with the runner's throwaway debug key, so neither can be updated in place: the
updater shows *Can't install this update* (different signing key). Back up (Settings -> Backup),
uninstall, install 3.9.2 or later, restore; from 3.9.2 on every release and pre-release is signed
with the same key and updates in place. The keystore and its credentials are kept by the owner
outside the repository; losing them means another reinstall for every user.

Add these in *GitHub → repository → Settings → Secrets and variables → Actions → New repository
secret* (or with `gh secret set NAME`):

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | the keystore file, base64-encoded on one line |
| `KEYSTORE_PASSWORD` | the keystore (store) password |
| `KEY_ALIAS` | the key alias inside the keystore, e.g. `quicknovel` |
| `KEY_PASSWORD` | the key password (same as the store password for a PKCS12 keystore made as below) |

Generate the keystore once, on your own machine (`keytool` ships with every JDK, e.g. Android
Studio's `jbr/bin/keytool`):

```
keytool -genkeypair -v -keystore quicknovel-release.jks -storetype PKCS12 \
    -keyalg RSA -keysize 4096 -validity 10000 -alias quicknovel \
    -dname "CN=QuickNovel, O=Techno453"
```

It asks for a password; use it for both `KEYSTORE_PASSWORD` and `KEY_PASSWORD`. Then encode it:

```
# Linux
base64 -w0 quicknovel-release.jks > keystore.b64
# macOS
base64 -i quicknovel-release.jks -o keystore.b64
# Windows PowerShell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("quicknovel-release.jks")) | Set-Content -NoNewline keystore.b64
```

and paste the content of `keystore.b64` as `KEYSTORE_BASE64` (or `gh secret set KEYSTORE_BASE64 < keystore.b64`).
Delete `keystore.b64` afterwards and keep `quicknovel-release.jks` and its password somewhere safe
outside the repository (`.gitignore` ignores `*.jks`, `*.keystore` and `keystore.b64` as a
safety net, but do not keep them in the checkout). **If the key is lost, installed apps can never
be updated again**: users would have to uninstall and reinstall.

How `app/build.gradle` uses it: `signingConfigs.release` reads `KEYSTORE_FILE` (the workflows
decode the secret to a temp file and set this), `KEYSTORE_PASSWORD`, `KEY_ALIAS` and
`KEY_PASSWORD` from the environment. Without `KEYSTORE_FILE` the release build type is signed with
the debug key, so `assembleRelease` always produces an installable APK. On CI that debug key is
generated fresh on every runner, so debug-signed releases cannot update each other; the release
notes say so. With `SIGN_DEBUG_WITH_RELEASE_KEY=true` (set by the pre-release workflow when the
secrets exist) debug builds are signed with the release key too, so pre-releases update in place.

The fork's key differs from upstream LagradOst's: an installed upstream QuickNovel (same
applicationId `com.lagradost.quicknovel`) cannot be updated to the fork; it has to be uninstalled
first (back up data in *Settings → App settings → Backup data*).

## Releasing a version

1. In `app/build.gradle` raise `versionCode` by one and set `versionName`, e.g. `56 → 57`,
   `"3.8.0" → "3.8.1"`.
2. Commit (`Release 3.8.1`) and push to `master`.
3. Tag and push the tag: `git tag v3.8.1 && git push origin v3.8.1`.
4. The Release workflow builds and publishes **QuickNovel 3.8.1** with `QuickNovel-3.8.1-arm64-v8a.apk`,
   `-armeabi-v7a.apk`, `-x86_64.apk`, `-universal.apk` and `QuickNovel-3.8.1-mapping.txt.gz`.
   A tag that does not match `v<versionName>` fails the workflow before building. The workflow
   can also be started by hand (*Actions → Release → Run workflow*, tag `v3.8.1`); the tag is then
   created on the chosen branch's head, and re-running for an existing release replaces its APK.

**Version numbers must stay single-digit per part** (`3.8.9 → 3.9.0 → 4.0.0`): the in-app
updater keeps upstream's check, which finds `\d.\d.\d` in the asset name and compares it with the
installed `versionName` as a string, so `3.10.0` would never be offered as an update.

## In-app updater (`util/InAppUpdater.kt`)

- **Release builds** (`com.lagradost.quicknovel`) check
  `api.github.com/repos/Techno453/QuickNovel/releases/latest` (pre-releases are excluded by
  GitHub) and offer the APK for this device when its `x.y.z` is greater than the installed
  versionName. `InAppUpdater.pickApkAsset` chooses among the release's `.apk` assets: the
  `-<abi>.apk` of the first ABI in `Build.SUPPORTED_ABIS` that has one (a Fold or any 64-bit
  phone: `arm64-v8a`; old 32-bit phones: `armeabi-v7a`; emulators: `x86_64`), else
  `-universal.apk`, else the first APK without an ABI suffix (releases from before the split,
  and the pre-release). Other assets such as the mapping file are ignored. All APKs of a release
  share one versionCode; switching between universal and a per-ABI APK is an ordinary update.
- **Debug builds** (`com.lagradost.quicknovel.debug`, installed next to the release app) check
  the rolling `pre-release` release instead and offer it when the run number in
  `…-pre.<run>.apk` is greater than `BuildConfig.CI_BUILD_NUMBER` (set from `QN_BUILD_NUMBER` by
  the pre-release workflow; `0` for local builds). Locally built debug apps skip the check on
  start-up, since a CI build is signed with another key; *Check for update* still works.
- Before starting the system installer the updater compares the downloaded APK's package name
  and signing certificates with the installed app. On a mismatch (debug vs release package, a
  self-built app vs a CI build, a debug-signed release) it shows *Can't install this update* with
  the reason and a button to the releases page, instead of letting the installer fail.

## Release APKs, shrinking and crash traces

**Per-ABI APKs.** `splits.abi` in `app/build.gradle` makes the release build produce
`app-arm64-v8a-release.apk`, `app-armeabi-v7a-release.apk`, `app-x86_64-release.apk` and
`app-universal-release.apk` in `app/build/outputs/apk/release/`. Most of an APK is ML Kit's
on-device translator (`libtranslate_jni.so`, 11-17 MB per ABI), so a per-ABI APK is about a
third of the universal one. Debug builds are not split: `androidComponents` in
`app/build.gradle` drops their per-ABI outputs, so `assembleDebug` still produces one universal
`app/build/outputs/apk/debug/app-debug.apk` (CI artifact, pre-release, emulator installs).

**R8.** Release builds are shrunk, optimized and obfuscated (`minifyEnabled`,
`shrinkResources`). The keep rules are in `app/proguard-rules.pro`, each with the reason. The
main one keeps the app's own package whole, because Jackson maps app classes (provider
responses, everything DataStore and backups persist, the translator's JSON, the updater's
GitHub models) by reflection. New code that uses reflection on a *library* class, or a new JSON
library, needs a rule there; then check a release build on a device (`assembleRelease`, install
`app-x86_64-release.apk` on an emulator) and look for `ClassNotFoundException`,
`NoSuchFieldException`/`NoSuchMethodException` or Jackson errors in logcat.
`JsonModelsRoundTripTest` round-trips the reflectively mapped model classes (unshrunk);
`app/build/outputs/mapping/release/usage.txt` lists what R8 removed and `seeds.txt` what the
keep rules matched.

**Mapping files.** A stack trace from a release build has obfuscated library class names
(`nt6.a`) and, thanks to `-keepattributes SourceFile,LineNumberTable`, real line numbers. Each
build writes `app/build/outputs/mapping/release/mapping.txt`; the Release workflow attaches it
to the GitHub release as `QuickNovel-<versionName>-mapping.txt.gz`. The mapping only fits the
exact build it came from (a local build has its own). To de-obfuscate a trace saved as
`trace.txt`:

```
gunzip QuickNovel-3.8.1-mapping.txt.gz
# retrace ships with the Android SDK command-line tools (cmdline-tools/latest/bin), or use R8 directly:
retrace QuickNovel-3.8.1-mapping.txt trace.txt
java -cp r8.jar com.android.tools.r8.retrace.Retrace QuickNovel-3.8.1-mapping.txt trace.txt
```

**Trimmed resources.** `packaging.resources.excludes` drops BouncyCastle's post-quantum
parameter tables (`org/bouncycastle/pqc/**`, ~8 MB uncompressed, ~4 MB in the APK), which come
with pdfbox-android's crypto dependency and are never used (PDF import does not need them).

## Provider tests

### Fixture tests (run in CI)

```
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest
./gradlew :app:testDebugUnitTest --tests 'com.lagradost.quicknovel.providers.*'
```

`app/src/test/java/com/lagradost/quicknovel/providers/` holds one `*FixtureTest` per CJK
provider (69shuba `Shuba69FixtureTest`, `QuanbenFixtureTest`, `SyosetuFixtureTest`,
`KakuyomuFixtureTest`, `NovelpiaFixtureTest`) that parse real pages recorded under
`app/src/test/resources/providers/<provider>/` through the providers' pure parsing functions
(search results, novel metadata and chapter order, chapter text). `ProviderParserTest` covers
edge cases with small hand-written `synthetic_*` pages, and `ProviderLanguageTest` checks that
every registered provider that looks CJK declares `zh`, `ja` or `ko` (translated search and
on-demand translation depend on it). The fixtures contain no copyrighted prose: see
`app/src/test/resources/providers/README.md`.

### Live smoke test (nightly, or by hand)

```
./gradlew :app:testDebugUnitTest --tests '*CjkProvidersLiveSmokeTest*' -Dlive=1
./gradlew :app:testDebugUnitTest --tests '*CjkProvidersLiveSmokeTest*' -Dlive=1 -Dlive.provider=Syosetu
```

(`LIVE=1` / `LIVE_PROVIDER=…` environment variables work too; without them the test is
skipped.) `BrowseListingsLiveTest` (every browse listing of 69shuba, Quanben and Syosetu: page 1
non-empty, page 2 new) and `ProviderFixesLiveTest` (chapter-name alignment, titles, status, tags)
are gated the same way. Each provider is a separate parameterized case, so one failing site does not hide the
others; a failed step is retried once after 5 s. 69shuba is checked with a known novel because
its search needs Android's WebView for a Cloudflare Turnstile, and it is *skipped* rather than
failed when Cloudflare blocks the network.

### Recording a new fixture

Fetch the page with `curl` or, for sites that refuse curl (69shuba) or serve GBK, with the
recorder that goes through the app's OkHttp stack and saves UTF-8:

```
./gradlew :app:testDebugUnitTest --tests '*RecordFixture*' \
    -Dlive.record=https://www.69shuba.com/book/90435.htm \
    -Dlive.record.out=69shuba/info.html -Dlive.record.referer=https://www.69shuba.com
```

then trim it, replace the prose with `app/src/test/tools/scrub_prose.py`, and add assertions to
the provider's `*FixtureTest`. Details in `app/src/test/resources/providers/README.md`.

The older instrumented provider tests in `app/src/androidTest/` still need a device or emulator
(`./gradlew connectedDebugAndroidTest`) and are not run in CI.
