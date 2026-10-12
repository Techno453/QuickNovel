# Library updates (new-chapter checks)

The library works like a reading tracker: novels in selected library categories are checked for
new chapters in the background, new ones show as a "+N new" badge and in a notification.

Code: `app/src/main/java/com/lagradost/quicknovel/updates/`.

## Settings

Settings > Library updates, stored in the default shared preferences:

| Key | Values | Default |
|---|---|---|
| `library_update_frequency` | `"0"` off, `"6"`, `"12"`, `"24"` hours | `"24"` (daily) |
| `library_update_unmetered_only` | boolean | `true` |
| `library_update_categories` | string set of `reading`, `plan_to_read`, `on_hold`, `completed`, `dropped`, `downloads` | `reading`, `plan_to_read`, `downloads` |

"Check for new chapters now" is in the settings group and in the library's add (+) menu; it uses
any network. Turning the checks on (or checking now) asks for the notification permission on
Android 13+.

## Scheduling and throttling

- `LibraryUpdateScheduler` enqueues a unique periodic WorkManager job (`library_update_periodic`)
  with the chosen period, a 1 hour flex window, an unmetered (or any) network and battery not
  low. It is re-applied on every app start with `ExistingPeriodicWorkPolicy.UPDATE`, so an
  unchanged schedule keeps its timing. "Off" cancels it.
- The manual check is a unique one-time job (`library_update_manual`, `KEEP`).
- `LibraryUpdateChecker` runs one check at a time per process. Novels are checked least recently
  checked first, rotating between sites; requests to the same site are at least 1.5 s apart;
  a run stops after 8 minutes (the rest go first next time). A failing novel only gets a
  `lastError`; after two network-level failures in a row (timeout, DNS, TLS, 403/429/5xx,
  Cloudflare) the rest of that site's novels are skipped with a "looks blocked or down" note.
  Imported books and sources without a chapter list are not checked.

## State

Per novel, as JSON in the DataStore (`rebuild_preference.xml`) under
`library_update_state/{id}` (the library's novel id): `lastKnownChapterCount`,
`lastChapterUrl`/`lastChapterName`, `lastCheckedAt`, `newChapters` (unseen), `lastNewChaptersAt`,
`lastError`/`lastErrorAt`. The comparison lives in `ChapterUpdateDetector` (pure, unit tested):
new chapters are counted after the last known chapter url when it is still listed, otherwise
from the count. On the first check the bookmark's cached chapter total (or the download's total)
is the baseline. A check also updates the bookmark's cached total, so "read/total" stays current.

Opening a novel's result page (or reading it) clears `newChapters` and its notification.
"Recently updated" sorts by the later of "new chapter downloaded" and "new chapters found".

## Notifications

Channel `quicknovel.library_updates` ("New chapters", low importance: no sound or pop-up).
One notification per novel ("+N new chapters", tap opens its result page) grouped under a
summary "New chapters in N novels" with "name +N" lines (tap opens the novel if there is one,
the library otherwise).
