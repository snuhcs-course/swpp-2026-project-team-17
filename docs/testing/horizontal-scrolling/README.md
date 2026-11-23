# Horizontal scrolling: investigation and fix

Status: **implemented and checked offline and on an emulator. Physical-device validation is pending.**
Nothing in this report shows that the issue is fixed on a Samsung device or any other phone.

## Problem and expected behavior

Reported: vertical scrolling generally works, but horizontal scrolling does not reliably change pages,
especially on the Android launcher home screen. A page may move partway and then return.

Expected: while scroll mode is on, holding gaze in the left or right zone sends a horizontal swipe that can
complete a page transition in a compatible paged interface. Vertical scrolling, wink mode switching, click
suppression, tracking-loss handling and interaction restrictions stay as they are.

This concerns the Android launcher and other apps. NoTouch's own Home screen (`MainActivity`) calls
`TrackerState.settingsStarted()`, which sets `interactionAllowed = false`, so external gaze actions are
blocked there by design. That restriction is unchanged; test on the Android launcher instead.

## Branches

| | |
|---|---|
| Source branch | `feature/texting` (unchanged) |
| Base commit | `293e2e3902e2f47c86cad07def4032a5917d9da5` (latest `origin/feature/texting` at checkout time) |
| Fix branch | `fix/horizontal-scrolling` |
| Initial working tree | clean |

## Findings in the current code

Traced path: gaze zone → `ScrollSettings.direction()` → `ScrollController` (350 ms hold, 1,100 ms repeat) →
`InteractionEngine` (`EyeAction.Scroll`) → `GazePipeline` → `TrackingService.perform()` →
`ControlAccessibilityService.scroll()` → `dispatchGesture()` → completion or cancellation callback.

- **Routing:** LEFT and RIGHT reach the service unchanged. Existing tests already covered zone selection
  and the `EyeAction` output.
- **Axis and sign:** correct. LEFT (reveal content on the left) moves the simulated finger right; RIGHT moves
  it left. Y stays constant for horizontal strokes.
- **Duration:** the configured 650 ms was the duration actually dispatched (`ScrollSettings.DURATION_MS`).
- **Coordinates:** gaze is normalized to `DisplaySpace.bounds()` (full display) and the stroke uses the
  same bounds in screen pixels, so they are consistent.
- **Overlay:** the gaze dot window is `FLAG_NOT_TOUCHABLE`, so it does not swallow injected touches.
- **Busy state:** `busy` is cleared by the completion or cancellation callback. Because the stroke is
  shorter than the repeat interval, repeats are not dropped.
- **Window events:** `VIEW_SCROLLED` and `WINDOW_CONTENT_CHANGED` only cancel a pending dwell click.
  `WINDOW_STATE_CHANGED` resets interaction only when the focused application window changes
  (commit `4fbc3ac`). This behavior is preserved.
- **The defect:** horizontal swipes reused the vertical stroke, 15% of the screen width over 650 ms.
  On a 1080 px / 420 dpi screen that is 162 px (about 62 dp) at roughly 95 dp/s.

AOSP Launcher3 (`PagedView`) completes a page change only when the drag exceeds 40% of the page width
(`SIGNIFICANT_MOVE_THRESHOLD = 0.4`) or ends in a fling faster than about 500 dp/s. Otherwise it snaps
back to the original page. AndroidX `ViewPager` needs about 60% without a fling, or a fling above 400 dp/s.
The old stroke met none of these conditions. That fits the reported "moves partway, then returns".

The tutorial animates its own `HorizontalScrollView` with `ValueAnimator`, so tutorial success never
exercised the injected gesture path.

## What supports the hypothesis, and what is still uncertain

Supported:
- The source analysis above.
- On the emulator's Pixel Launcher, the old stroke moved the page partway and it snapped back, in 6 of 6
  trials. The new stroke changed the page in 6 of 6 trials (see below).
- A threshold probe on the same launcher matched the Launcher3 rules: a long slow drag switched pages,
  a short fast fling switched pages, and short slow strokes did not.

Uncertain:
- Samsung One UI Home and other vendor launchers may use different thresholds. They were not tested.
- The complete gaze flow (camera → hold → dispatch) was not run on any device.
- The stroke starts at the screen center. A widget there that consumes horizontal drags may take the
  gesture instead of the launcher. On the emulator the center was empty space.
- If a launcher sends `WINDOW_STATE_CHANGED` while no focused application window is reported, the existing
  conservative rule resets scroll mode. This did not happen on the emulator.
- Right-to-left layouts were not tested.

## Change

Only external LEFT/RIGHT swipes changed:

| | Before | After |
|---|---|---|
| Horizontal stroke length | 15% of width, centered (42.5% → 57.5%) | 50% of width, centered (25% → 75%) |
| Horizontal duration | 650 ms | 300 ms |
| Vertical (Y) position | screen center, constant | screen center, constant |
| Average speed, 1080 px / 420 dpi | ~95 dp/s | ~686 dp/s |
| Average speed, narrow 360 dp phone | ~83 dp/s | ~600 dp/s |
| Vertical swipes | 15% of height, 650 ms | unchanged |
| Tutorial animation | 15%, 650 ms | unchanged |
| Zones, 350 ms hold, 1,100 ms repeat | | unchanged |

Why these values: 50% is above the 40% Launcher3 distance rule, and 300 ms brings the average speed above
the ~500 dp/s fling rule even on a 360 dp-wide phone. A paged view can therefore accept the gesture by
either rule. Starting at 25% / 75% of the width keeps the touch away from the edge back-gesture zones,
and the center line keeps it away from the status and navigation bars. These are starting values and
have not been tuned on real devices.

How the change is wired:
- `ScrollSettings.swipe()` now returns a `ScrollSwipe(from, to, durationMs)` and picks the horizontal or
  vertical parameters by axis.
- `ControlAccessibilityService.scroll()` dispatches `swipe.durationMs`, not the shared constant. Without
  that change, the new duration would have been configured but never used.
- The shared `DISTANCE_FRACTION` and `DURATION_MS` are untouched, so vertical swipes and the tutorial
  behave as before.

Out of scope, and not done: launcher detection, package-specific workarounds, new scroll modes, fallback
gestures, changes to gaze prediction, calibration, smoothing or eye-state logic, text entry, networking,
permissions or dependencies.

Trade-off: in free-scrolling horizontal content (carousels, `HorizontalScrollView`), each step now moves
much further. On the emulator it moved 779 px instead of 148 px, about 72% of the screen width including
fling. This should be judged on a phone.

## Files changed

- `app/src/main/java/dev/gazedot/EyeGestures.kt`: `ScrollSwipe`, horizontal-only constants,
  `swipe()` picks the parameters by axis.
- `app/src/main/java/dev/gazedot/ControlAccessibilityService.kt`: `scroll()` dispatches the swipe's own
  duration.
- `app/src/test/java/dev/gazedot/HorizontalScrollTest.kt` (new, 7 tests).
- `app/src/test/java/dev/gazedot/EyeGesturesTest.kt`: the geometry test now checks finger sign and axis
  for every direction, instead of assuming one shared distance.
- `app/src/test/java/dev/gazedot/ApplicationWindowTrackerTest.kt`: a horizontal version of the
  same-window update test from `4fbc3ac`.
- `docs/testing/horizontal-scrolling/README.md`: this report.

## Checks performed

Environment: Windows 11, Gradle wrapper 8.13 / AGP 8.9.2 / Kotlin 2.1.20. Oracle JDK 23.0.1 was set with
`JAVA_HOME` for these commands only. The README recommends JDK 17 or 21, but neither was installed; the
bytecode target is still 17. The local Android SDK was set with `ANDROID_HOME`. No global settings were
changed, and no `local.properties` was created.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon          # baseline
.\gradlew.bat clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon    # final
git diff --check
```

### Automated results

| | Baseline (`293e2e3`, unmodified) | Fix branch (final) |
|---|---|---|
| Build | BUILD SUCCESSFUL | BUILD SUCCESSFUL |
| Unit tests | 101, 0 failures | 109, 0 failures (8 new) |
| Android lint | 0 errors, 36 warnings | 0 errors, 36 warnings (identical issue set) |
| `git diff --check` | – | clean |

There were no baseline failures. All lint warnings are pre-existing (`UseKtx`, `GradleDependency`,
`ImplicitSamInstance` and others); the change adds none.

New and updated tests cover:
- the gaze zone → `EyeAction.Scroll` → dispatched stroke chain for LEFT and RIGHT (finger direction,
  constant Y, horizontal length and duration, no clicks);
- vertical strokes pinned to the literal pre-fix values (540,1020) ↔ (540,1380) at 650 ms, plus the
  tutorial's shared constants;
- horizontal strokes on the center row, centered, inside 20–80% of the width, with mirrored endpoints, on
  four screen shapes;
- the stroke exceeding the 40% distance and 500 dp/s fling reference points on a 360 dp phone;
- every stroke ending before the next repeat can be dispatched;
- the neutral zone stopping horizontal scrolling until a new 350 ms hold;
- tracking loss turning scroll mode off;
- same-window updates keeping horizontal scroll mode, while another app resets it.

To confirm the tests detect real mistakes, each of these deliberate bugs was injected one at a time and
then reverted:

| Injected bug | Tests that failed |
|---|---|
| Reversed horizontal finger direction | `horizontalAndVerticalSwipesUseTheCorrectAxisAndFingerDirection`, `lookingLeftOrRightDispatchesAHorizontalStrokeThatRevealsThatSide` |
| Horizontal reverted to the shared 15% / 650 ms | `lookingLeftOrRight…`, `horizontalStrokeExceedsCommonPageSwitchDistanceAndFlingSpeed` |
| Vertical accidentally given the horizontal fraction | `verticalSwipesKeepThePreviousStrokeAndTiming` |
| Horizontal stroke also moving along Y | `horizontalAndVertical…`, `horizontalStrokesStayOnTheCenterRowAndAwayFromScreenEdges`, `lookingLeftOrRight…` |

JVM tests cannot run `dispatchGesture()`. The final two-line mapping from `ScrollSwipe` to `Path` and
`StrokeDescription` was checked by review and by the emulator harness below, which mirrors it.

### Emulator observations

Setup: an existing Pixel 6 AVD, Android 17 (API 37, `google_apis` x86_64), 1080×2400 at 420 dpi, with
Pixel Launcher (Launcher3-based). It was started with `-read-only -no-snapshot-save`, so app installs and
settings were not kept. The saved quickboot snapshot images were not modified. Snapshot metadata and
overlay timestamps were updated when the AVD was opened.

Harness: a throwaway accessibility-service APK, built outside this repository and not committed. It
compiles the fix branch's `EyeGestures.kt` and `ApplicationWindowTracker.kt`, builds the `Path` and
`StrokeDescription` exactly as `ControlAccessibilityService.scroll()` does, and is triggered by an adb
broadcast. It bypasses the camera, gaze pipeline and NoTouch's interaction guards, so it tests gesture
delivery only, not the gaze-controlled flow. "Legacy" below means the base-commit formula
(15%, 650 ms).

**Launcher, three home pages, starting on page 2 (3 trials each):**

| Stroke | Direction | `dispatchGesture` / callback | Page afterwards |
|---|---|---|---|
| Legacy 15% / 650 ms | RIGHT | accepted, completed (~655–666 ms) | stayed on 2 (3/3) |
| Legacy 15% / 650 ms | LEFT | accepted, completed | stayed on 2 (3/3) |
| New 50% / 300 ms | RIGHT | accepted, completed (~303–308 ms) | 2 → 3 (3/3) |
| New 50% / 300 ms | LEFT | accepted, completed | 2 → 1 (3/3) |

The page was identified from the visible workspace icons after each gesture. A screenshot taken during a
legacy stroke showed page 2 shifted by about 60 px, and the final frame showed it back in place. This
matches the reported partial movement. Every legacy gesture was accepted and completed, which confirms
that accepted and completed do not mean the page changed.

**Threshold probe (RIGHT, 2 trials each):** 50% / 650 ms switched; 45% / 650 ms switched; 35% / 650 ms did
not; 15% / 100 ms (~617 dp/s) switched; 15% / 200 ms (~309 dp/s) did not; 30% / 300 ms (~411 dp/s) did
not. These few points fit the Launcher3 rules (about 40% distance or about 500 dp/s). They are not a
precise measurement.

**Window events during page switches:** the launcher sent `VIEW_SCROLLED` from the same window and no
`WINDOW_STATE_CHANGED`. Under the `4fbc3ac` logic, that only cancels a pending click and keeps scroll mode.

**Other containers (settled offsets):**

| Container | Legacy | New |
|---|---|---|
| `HorizontalScrollView`, RIGHT / LEFT | +148 / −148 px | +779 / −779 px |
| Vertical `ScrollView`, DOWN / UP | +371 / −371 px | +371 / −371 px (identical) |
| Settings list, DOWN, DOWN, UP (new build) | – | reported scrollY 364, 726, 357 |

### Physical device

Not performed. No phone was available, and none was connected to or modified.

## APK

`app/build/outputs/apk/debug/app-debug.apk`: 23,433,258 bytes, SHA-256
`f3ae67cbac22a46cc12d29cf7a1683004199f0468a012c7f009fb05a69667100`

This is a local debug build and is not committed.

## Physical-device validation (pending)

Install the debug APK, enable the NoTouch accessibility service, calibrate, and turn scroll mode on with a
wink. Record the device model, launcher and Android version.

1. **Launcher:** set up at least three home pages and start on the middle one. Look right and hold:
   expect the next page. Look left and hold: expect the previous page. Repeat each direction 5 times. Note
   whether each page **completed** the transition or only moved and returned.
2. **Edge pages:** on the first and last page, check that nothing breaks (no app opening, no stuck state).
3. **Another app:** horizontal content such as a photo viewer, tabs or a carousel, in both directions.
   Note whether one step moves too far.
4. **Vertical:** a long list, scrolling up and down. It should feel the same as before this change.
5. **Repeat and neutral zone:** hold right for several repeats, then look at the center. Scrolling should
   stop and resume only after a new hold.
6. **Mode switching:** a wink turns scroll mode on and off. With scroll mode off, left and right do not
   scroll.
7. **No clicks:** while scroll mode is on, long gaze on icons or buttons must not open anything.
8. **Tracking loss:** cover the face during scroll mode. The mode turns off, and tracking recovers when the
   face returns.
9. **Restrictions:** on NoTouch's own screens, during calibration, settings, recovery, and while the
   writing overlay is open, no external scroll happens.
10. **Center widget:** if the center of a home page holds a widget, note whether the swipe still changes
    the page.

Report the results against this list before treating the fix as validated.
