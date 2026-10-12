# Android backup rules

The app keeps `android:allowBackup="true"` and ships two rule files, referenced from the
`<application>` element of `AndroidManifest.xml`:

| File | Attribute | Used on |
|---|---|---|
| `res/xml/backup_rules.xml` | `android:dataExtractionRules` | Android 12+ (API 31+), separate cloud and device-transfer rules |
| `res/xml/full_backup_content.xml` | `android:fullBackupContent` | Android 6 to 11 (API 23-30), one rule set for both |

These rules cover Android's own backup (Google cloud backup and the device-to-device transfer
when setting up a new phone). The in-app *Backup* / *Restore* in Settings is separate: it writes
the two preference files to a JSON file and does not include the translator database.

## What is on disk

| Data | Where | Backed up |
|---|---|---|
| Library, bookmarks (`result_bookmarked*`), download metadata (`downloads_data/*`), reading progress, history, new-chapter state (`library_update_state/*`) | `shared_prefs/rebuild_preference.xml` | cloud + transfer |
| Settings | `shared_prefs/<package>_preferences.xml` | cloud + transfer |
| Translations, glossary, Story Bible, summaries | `databases/translator_database` with `-wal` and `-shm` (Room's default WAL journaling) | cloud + transfer |
| AI provider API keys and translator options | `shared_prefs/translator_secure_prefs.xml` (EncryptedSharedPreferences; its two Tink keysets are stored inside the same file) | **never** |
| Plain fallback of the same, used when the Keystore is unusable | `shared_prefs/translator_prefs_fallback.xml` | **never** |
| Downloaded chapters (`<index>.txt`), posters, generated `local_epub.epub` | `files/<source>/<author>/<name>/` | transfer only |
| Imported EPUBs and PDFs | `files/Download/...`, `files/importsourcepdf/...` | transfer only |
| In-app update APK | `files/Download/apk/` | **never** |
| Image cache, PDF import temp files, WebView HTTP cache | `cache/` | never (Android skips `cache/` itself) |
| WorkManager's database | `no_backup/` | never (Android skips `no_backup/` itself) |

## Why

- **Encrypted preferences are excluded everywhere.** `translator_secure_prefs.xml` is encrypted
  with a master key that lives in the Android Keystore and is never backed up or transferred.
  A restored copy cannot be decrypted on the new device (or after a reinstall): building the
  encrypted prefs then fails, the app falls back to the plain prefs, and the stale file keeps
  getting in the way. The fallback file holds the same API keys in plain text, so it stays on
  the device too. After a restore, re-enter the API keys in Settings > AI Translation.
- **Cloud backup is capped at 25 MB per app.** If the backup is larger, Android rejects the
  whole snapshot for the app (and keeps the previous one), so cloud backup only includes the
  small, irreplaceable data: the preferences and the translator database. Downloaded chapters
  can be fetched again from the source (open the library and download or refresh again).
- **Device-to-device transfer has no size cap**, so on Android 12+ it also moves `files/`:
  downloads, generated EPUBs and imported books arrive on the new phone as they were.

## What can exceed 25 MB

- `translator_database`: it stores every translated chapter in full (roughly 10-30 KB of text
  per chapter), so a few thousand translated chapters are enough to pass the cap. When that
  happens the app's cloud backup stops updating; device transfer is unaffected.
- `rebuild_preference.xml` grows with the library and reading history but normally stays in the
  low megabytes.
- Downloads and imported books are the largest data by far, which is why they are only in the
  device transfer.

## Limitations

- On Android 6 to 11 one rule set serves both cloud backup and device transfer, so it follows
  the cloud rules and downloads/imported books are not transferred there. (`requireFlags` could
  limit an include to device transfer, but Android 6 to 8.1 ignore it and would then push the
  downloads into the 25 MB cloud backup.)
- After a cloud restore the library lists downloaded novels from their metadata, but the chapter
  files are gone until they are downloaded again.
