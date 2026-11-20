# Calibration and Text Entry — Gyulim Kim

Status: implementation and test preparation. Real-device evaluation results are **not yet collected** for this build.
Branch: `gaze-tracking-improvements`. APK: v26, `0.12.1-text-entry-evaluation`.

## 1. Calibration handoff

The baseline is front-camera CameraX → MediaPipe facial/iris landmarks → personal open-eye/iris measurement → frozen Caffe iTracker → five-point screen mapping → filtering → interaction. Iris setup is required; there is no automatic skip. Users do not train the iTracker network. The fitted screen mapper is session-local. Large head motion and cross-device accuracy remain limitations.

The `feature/retargeting` branch has NOT been merged. It uses an older landmark pipeline and applies a bounded global translation after five check targets. Do not describe the current APK as the integrated retargeting build. Current `AccuracyActivity` separately fits a residual correction and checks held-out targets; this is not that branch's implementation.

Use the same APK, calibration and device for the basic-pipeline test. For a future retargeting A/B comparison, keep image preprocessing/model/targets fixed and change only the post-calibration correction. A successful build or completed calibration is not proof of improved gaze accuracy.

## 2. Text-entry evaluation entry and implemented behavior

Home → **텍스트 입력 평가**. Start the camera, enable accessibility and control, and complete calibration first.

The evaluation page contains a goal phrase, a target field, a decoy field that must stay empty, mode/interaction selectors, condition notes, start/complete/fail controls, and JSON export. These are operator controls; an experimenter may start/stop timing, but assistance with the actual input must be marked.

The target field is connected to the production accessibility adapter using only `dev.gazedot:id/eval_editor` plus an identified evaluation header. This does not enable writing to arbitrary app fields. Existing messaging adapters remain unchanged.

- **Voice:** focus the target; rapidly blink both eyes twice; speak the target sentence when ready. The existing recognition service inserts into the original field. No automatic Send action exists.
- **Stored phrases:** focus target → **NoTouch로 작성** → **자주 쓰는 문장 선택** → select the requested phrase. The default phrases must be restored or their actual contents recorded before testing.
- **Direct input + AI candidates:** focus target → **NoTouch로 작성** → **직접 입력하기**. Use the existing input UI and, when available, candidates. Existing server configuration and explicit online-consent flow still apply. Record network failures; do not replace them with successes.

The writing UI was previously discoverable only in specific supported messaging fields, explaining why it could be hard to find from the main screen. The evaluation page is a controlled test surface, not evidence of external-app compatibility.

A voice insertion gate was repaired: recognition callbacks must be able to verify and insert into their original field while the voice session is active. Window/focus/original-text/selection checks remain in place.

## 3. Quick evaluation protocol

One fixed phone/position/lighting condition, one successful calibration. Enter participant code (not real name), distance, lighting and glasses in notes. Use only supplied dummy phrases, not private messages.

Run five trials per method (15 total). Keep voice, stored-phrase and direct/AI results separate. Optionally repeat as a touch baseline using the corresponding selector; never pool touch and gaze trials. Suggested task order may be rotated across participants to reduce practice effects.

1. Select method and **시선으로 조작** or **터치로 기능만 확인**.
2. Press **시행 시작**; the target field is cleared and focused; timing starts.
3. Perform the stated method. Close the writing overlay when finished so the operator can record the result.
4. Mark any touch/keyboard help during input. Press **완료 기록** or **실패 / 포기 기록**. Suggested practical timeout: 60 seconds, enforced by the operator.
5. Leave the decoy empty. Record visible errors in conditions/notes before the next trial.
6. Export JSON. Navigating away during a trial records an interruption instead of a success.

Normalization: Unicode NFC plus trim of outer whitespace only. Internal spaces and punctuation count toward errors. Korean CER here is code-point based (composed Hangul syllables), not jamo based. CER may exceed 100% for many insertions.

### Measures
- Start success **per trial**: `voice_ready` or `writing_opened` observed, divided by completed/failed trials. This is not per-blink recognition accuracy.
- Task success: operator completed the trial, exact normalized text, no decoy changes, and a production-path insertion event was observed.
- Duration: operator start to record press, including navigation and correction. It is not pure model latency.
- CER: Levenshtein distance / target code-point count. Completed-only mean must be shown alongside failures.
- Wrong-field change count, text-change events, replacement/deletion events, accepted AI candidate events.
- Touch assistance and interruptions are separate. Replacement/deletion events are NOT a reliable count of deliberate user corrections.

The direct/AI test is end-to-end text entry. It does not establish semantic candidate quality or standalone LLM accuracy; candidate usage may be zero. Report accepted candidate events and network conditions.

### Proposed acceptance targets (team to confirm before collection)
- No wrong-field insertion or automatic Send.
- At least 4/5 task successes per method under the declared condition.
- Completed-trial mean CER ≤ 0.10 and median duration ≤ 60 s, with all failures reported separately.
- Interrupted trials are reported, not silently discarded or counted as passes.

These are provisional project targets, not requirements quoted from the TA and not achieved results.

## 4. UAT stories

Given a calibrated phone and focused test field, when the user invokes voice input and speaks the target, then the recognized text appears in that field without sending it. Verify both observed text and voice events.

Given a supported field and the writing panel, when a stored phrase or composed draft is inserted, then its text appears in the original field and the decoy remains empty.

Given a trial in progress, when input is cancelled, fails, or the activity is backgrounded, then a failure/interruption is recorded and no successful result is fabricated.

## 5. Results

| Check | Status |
| --- | --- |
| Real phone trials / participant results | NOT RUN for v26 |
| External messaging-app compatibility | NOT RUN for v26 |
| AI service availability and candidate quality | NOT VERIFIED in this task |
| Automated tests and APK build | See build verification record delivered with APK |

Do not copy earlier version test counts as a v26 measurement.

## 6. Export and analysis

JSON is saved in app-private `files/text-entry-evaluation/` after events and trial completion. Use **현재 / 최근 결과 JSON 내보내기** to save it to Files/Drive through Android's document picker. It includes dummy target and entered text, conditions, timings and event names; it contains no audio/video. Normal online candidate processing still follows existing consent; evaluation logs themselves are not uploaded automatically.

From the repository root:

```powershell
python tools/text_entry_eval/analyze.py "C:/path/to/export.json" --output "C:/path/to/summary.json"
python -m unittest discover -s tools/text_entry_eval -p test_analyze.py
```

## 7. Next alpha scenario

Create a dummy calendar event (“팀 회의”, tomorrow 15:00) using voice entry, then inspect the title/time before saving. Calendar writing-panel support is NOT currently an adapter capability; test voice insertion separately and record unsupported UI. Use a test calendar. This is a proposed next-iteration scenario, not a completed experiment.

## 8. Build verification — 2026-11-20

- Android JVM suite: 133 tests, zero failures/errors/skips.
- Python analysis suite: 2 tests passed.
- Debug APK assembly and Android Lint passed.
- APK signature verified; official iTracker weight hash and MediaPipe asset presence verified.
- ADB device list empty. UI, recognition accuracy, external-app support and live AI responses were not physically tested.
