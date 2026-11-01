# Follow mode (eye-tracked reading)

The reader can follow your eyes with the front camera: in scroll mode the text glides up as you
read, in paged mode the page turns after its last line. Built on the gaze test
([gaze-test.md](gaze-test.md)), which measured what a phone camera can and cannot see. In the
release app from 3.9.5 (2026-11-01), still experimental.

## How it reads you

- **Lines are counted from return sweeps**, the jump back from the end of a line to the start of
  the next (`FollowEngine`, `LineSweepDetector`). In the reader they came out smaller and slower
  than in the gaze test: 0.4 to 0.7 of a line over half a second (about 1.0 in a third of one in
  the test), and 0.3 to 0.45 once the text was gliding, so the first reader session counted only
  11 of about 20 lines with the test's fixed rule (a 0.40 drop within 0.4 s). Now:
  - the drop is looked for over 0.7 s, and the rest of a slow sweep's way down is the same sweep;
  - a full line needs 0.55 of your recent sweeps (median of the last 7, between 0.22 and 0.40;
    0.40 until three are known);
  - a line read for longer than it should take at your pace (learned from full lines, 2 s until
    known) needs less, down to 0.6 of that at twice the time;
  - a short line needs its width times 1.5 of that (its sweep is about as wide as the line), at
    least 0.18; under 35% of the width (a short line of dialogue) it ends after the time it
    should take;
  - nothing counts in the first 1.5 s after starting (the eyes come back from the menu: the first
    session counted two lines there) or 0.8 s after a jump.

  Replayed, the first session counts 22 line ends instead of 11; the gaze test's marked trial
  still finds 14/14 with 1 or 2 extra.
- **Scroll mode glides.** The line you are on rises to about a third of the way down the text
  area and stays there: the text moves up continuously by the share of the line you should have
  read by now (time on the line against your pace), so the next line arrives at that height as
  your eyes sweep to it. Reading faster than your pace closes the gap in about a third of a
  second; reading slower, the text moves up at most one line and then waits for the sweep. It
  never scrolls back by itself and stands still while your eyes are away.
- **Paged mode turns** on the sweep after the last line of the page, or after 2.5 lines' time
  (at least 4 s) if no sweep comes.
- **Up-down only guards a paged turn.** It cannot place the eyes on a line, and its zero point
  drifts by many lines within a minute (in the marked trial, reading sat 10 to 15 lines off the
  calibration). So it is compared within a page only: how far the eyes went down between the
  page's first seconds and its last line, against how far they went on earlier pages. Less than
  35% of that holds the page for another line, up to 4 times. The direction and size are learned
  from the first two pages read without corrections; after a jump there is no guard until the
  next turn. Even re-zeroed per page, the marked trial was off by up to 4 lines with no rereads,
  so the guard only catches large mistakes. Scroll mode keeps your eyes at one height, so it has
  no guard. Placing the eyes in sixths of the screen to catch a count that fell behind was
  checked on the 2026-11-01 sessions and does not work: with the eyes held at one height the
  signal moved by a median 2 to 4.5 sixths within 20 s (3 screen heights in a minute with the
  phone on a table), while 3 lines behind is half a sixth; in the marked trial, with the best
  offset known in hindsight, 31% of half-second readings fell in the right sixth (chance 17%).
- **The bar** left of the text marks the counted line: blue while following, amber when a turn
  is held, grey while paused (no face, eyes away or closed for 1.5 s), faint grey while paused
  with the phone lying at rest.

## Using it

1. Tap the text to show the bars, then **Follow** in the toolbar, **Start following**. The
   camera permission is asked once; the face model downloads once (3.7 MB, from
   `storage.googleapis.com`). Camera frames never leave the phone and are not saved.
2. It looks for a front camera that sees your face (a foldable has two; it tries the next after
   3 s), then calibrates the first time on this screen: look at the orange dot at the start of a
   line, then at its end (2 s each). Folded and unfolded, portrait and landscape each keep their
   own camera and calibration. **Recalibrate** in the same menu redoes it; **Switch camera**
   picks the other front camera.
3. Read. The count starts at the first line on screen.
   - **Volume up** moves the counted line back one (a reread was counted).
   - **Volume down** moves it forward one (scroll mode) or turns the page (paged mode).
   - Dragging the text restarts the count at the line nearest the reading height (scroll mode;
     put the line you are on there). Turning a page by hand or opening a chapter restarts it at
     the first line on screen.
4. **Putting the phone down**: following goes on while the camera sees your face (leaning over
   the phone on a table works; if it misses more than when held, **Recalibrate** with it lying
   down). Without a face the count pauses and the text stands still; scroll by hand as usual (the
   count restarts as in step 3).

Battery: no preview, 15 frames a second (where the camera has such a mode, otherwise every other
frame is analysed), a quarter of that while no face has been seen for 3 s, about one a second
when the phone also lies still and screen-up for 2 s, and nothing in the background. In the
marked trial sweeps were found as well at 10 or 15 frames a second as at 30 (14/14). Size:
MediaPipe adds about 10 MB of native code to each APK.

## Data

Each session writes `follow-*.csv` to the app's files (`Android/data/<package>/files/gaze/`);
**Share follow logs** in the Follow menu sends them. A row per frame while following (`t_ms`,
`valid`, `iris_norm`, `iris_v_raw`, `openness`, the counted `line` of `lines` on screen, its
`line_width`, `line_last`, the engine's `event` and its `cause`, SWEEP or TIMEOUT) and a row per
`ANCHOR`, `BACK`, `FORWARD`, `MANUAL_TURN`, `MOVED`, `REST` and `PICKED_UP`. The header names
the screen, camera, reading mode and calibration.

## Code

- `ui/reader/ReaderFollow.kt`: what the reader sees of it. `ReadActivity2` adds the toolbar
  button and passes volume keys; `ReaderScrollController` (`followLines`, `followScrollBy`) and
  `PagedReaderController` / `PageTextView` (`followLines`) give the lines on screen
  (`TextVisualLine.left` / `right`) and move the text.
- `gaze/` (main source set): `GazeFollower` (states, calibration, the counted line, the glide),
  `FollowEngine` (pure, the rules above), `FollowCamera`, `RestDetector` (`RestRule`, pure),
  `FollowOverlay`, `FollowLog`, `FaceModel`, and from the gaze test `FaceTracker`,
  `FaceGeometry`, `GazeCalibration`, `LineSweepDetector`. Tests in `src/test/.../gaze`.
- MediaPipe has no consumer R8 rules. `proguard-rules.pro` keeps `com.google.mediapipe.**`,
  Flogger's classes (it finds its caller by class name: renamed, the graph fails with "no caller
  found on the stack") and the fields of protobuf-lite messages (found by name: "Field typeUrl_
  for com.google.protobuf.Any not found"). Both failures only show in a shrunk build, so the
  `gaze` build type is R8-shrunk like a release: install it and open **Gaze test** (or follow
  mode in **QN Follow**, its copy of the app) before releasing a MediaPipe or R8 change.
- MediaPipe needs Android 7 (API 24); the manifest overrides its minSdk and `ReaderFollow.create`
  offers no follow mode below that.
