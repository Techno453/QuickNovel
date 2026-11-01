# Gaze test (eye-tracked line following)

An experiment, not a feature: can a phone's front camera see where the reader is in the text well
enough for the reader to follow them (scroll as they read, pause when they look away)? The
reader's follow mode built on it is in [gaze-follow.md](gaze-follow.md).

A front camera cannot tell which line someone is looking at (a few degrees of error against a
line height of about one degree). It may still see the **return sweep**: at the end of every line
the eyes jump back across the whole column, roughly 10–12 degrees on a phone, which is far larger
than the noise. Counting sweeps counts lines read. This test measures whether that works on a
given phone, face and lighting.

## Build and install

The camera, face tracking and sweep detection are in the main app now (follow mode,
`app/src/main/java/.../gaze`, tests in `app/src/test`). The test screen and its analysis
(`GazeTestActivity`, `TrialStats`, `DotResolution`) live in their own build type, `gaze`
(`app/src/gaze`, tests in `app/src/testGaze`), which is the release app shrunk by R8 plus this
screen, signed with the debug key:

```
./gradlew :app:testGazeUnitTest --tests "com.lagradost.quicknovel.gaze.*" :app:assembleGaze
adb install -r app/build/outputs/apk/gaze/app-gaze.apk
```

It installs as `com.lagradost.quicknovel.gaze`, next to the release and debug apps, with two
launcher icons: **Gaze test** and **QN Follow**, that build's copy of the app (with its own
library and settings), for trying follow mode before a release. The first start downloads MediaPipe's face model (3.7 MB, from
`storage.googleapis.com`) into the app's files. Camera frames never leave the phone.

## Running a trial

1. Hold the phone as when reading, in normal light (the camera needs to see the eyes clearly).
   On a foldable, tap the camera preview until it finds the face.
2. **Calibrate**: look at the orange dot as it moves: the left and right end of a line, then the
   first and the last line. The status shows an SNR per signal and direction, the distance
   between the dots in noise units. Under 3 the signal is too noisy to use.
3. **Read**: read the passage normally and press **volume up** each time you start a new line
   (also after rereading one: only real line changes). **Volume down** or **Done** ends the trial.
4. The status scores the trial against those presses: per signal the sweeps found, how many
   presses had a sweep within 0.8 s and how many sweeps had none (rereads, regressions or false
   detections), and the up-down signal's median error in lines. The plot shows the trial:
   reading should look like a sawtooth, a slow climb along each line and a sharp drop at its
   end, with a green mark on every drop; the presses are orange ticks along the top.

## Results so far (Galaxy Z Fold, cover screen, one reader)

- **Calibration**: SNR 17–19 (iris) and 17–24 (look) left-right, 19 and 24 up-down.
- **Return sweeps are visible.** In a marked trial (15 lines, 14 presses, 31 s) a plain drop of
  0.40 within 0.4 s found **14/14 line changes with 2 extra** (iris) and **14/14 with 1 extra**
  (look); the press came about 0.1 s after the sweep. Extras include rereads, which look the same
  as a sweep in the left-right signal.
- **Only the drop counts.** The reading range drifted after calibration (lines read from -0.3 to
  0.6 instead of 0 to 1), so rules on where a drop starts or lands missed most sweeps. Letting a
  fast rise lock out the next sweep for 0.9 s also hid half of them (a quick jump to the last word
  often comes just before the sweep); rises no longer do.
- **Up-down cannot tell lines apart on a phone.** While reading, the up-down step at a line
  change was 0.3–0.5 noise widths (d') for every up-down signal, a coin flip. In the dot test
  (long-press **Calibrate**: a dot on every second line, each twice in random order, 1.5 s each,
  no reading) the best case was **±1.8 lines per one-second look** for the iris (±1.5 with a
  drift term), ±2.1 for the look blendshapes; combining signals did not help and head tilt
  carried nothing. Single lines need about ±0.3. The signal is real and in order down the page,
  so it can place the eyes in a third or a quarter of a 15-line page, but it drifts by a few
  lines within 20 s. Rereads have to be handled by pace or by the reader, not by up-down.
- **Up-down as a running check on the line count** (catch a counter that ran ahead on rereads)
  looked good only with hindsight: zeroed on the whole trial, a 6 s window stayed within 2.5
  lines and caught a 2-line gap in about 4.5 s. Live it cannot be zeroed that way: reading sat
  10 to 15 lines off the calibration, and zeroed on a page's first seconds with the slope of an
  earlier page it was still up to 4 lines off with no rereads. Follow mode
  ([gaze-follow.md](gaze-follow.md)) therefore uses it only to hold a page turn when the eyes
  plainly stayed high.
- **15 frames a second are enough.** Analysing every second or third frame of the marked trial
  still found 14/14 line changes.

**Next** cycles through the built-in passages; **Paste** uses the clipboard (copy a chapter from
the reader). Each passage is cut to the lines that fit and shown as one paragraph, so every line
but the last spans the full width. Tap the plot to go back to the live signal.

## What to look for

- **Works**: sweeps within one or two of the expected count, trial after trial, few rises.
- **Signal there, detector off**: the sawtooth is visible in the plot but the count is wrong.
  The thresholds in `LineSweepDetector` (`minDrop`, `windowMs`, `minIntervalMs`) can be tuned
  from the CSVs.
- **No signal**: no sawtooth, SNR under 3. Only the coarse version is realistic on that phone:
  scroll at the reader's pace and pause when the face or eyes leave the screen.

Two signals are tried side by side: `iris`, the iris centre between the eye corners from the
landmarks (`FaceGeometry`), and `look`, MediaPipe's eye-look blendshapes.

## CSV

**Share** sends every trial (`Android/data/com.lagradost.quicknovel.gaze/files/gaze/*.csv`, or
`adb pull` that folder). The `#` header lines hold the device, line count, trial window,
calibration and `marks_ms`, the volume-up presses. One row per camera frame:

| Column | |
|---|---|
| `t_ms` | Frame time (uptime ms, same clock as the marks) |
| `face`, `valid` | Face found; face found and eyes open (blinks are left out of detection) |
| `iris_raw`, `iris_norm` | Iris position, raw and calibrated (0 = line start, 1 = line end) |
| `look_raw`, `look_norm` | Blendshape look, raw and calibrated |
| `iris_v_raw`, `iris_v_norm` | Iris up-down, raw and calibrated (0 = first line, 1 = last line) |
| `look_v_raw`, `look_v_norm` | Blendshape up-down look, raw and calibrated |
| `openness`, `blink` | Lid gap / eye width; MediaPipe's blink score |
| `head_turn`, `head_tilt` | Nose along the cheek axis and the forehead-chin axis (head movement) |
| `iris_event`, `look_event` | `SWEEP` or `RISE` where a detector fired |

Events in the first 800 ms (eyes travelling from the Read button) and the last 500 ms (to the
Done button) are not counted (`TrialStats`).
