# v39: complete Korean alternatives and response reuse

- Relay instructions now request two complete token alternatives (e.g. 도 -> 도와줘 / 도와줄게), never incremental suffix syllables. The existing whitespace-free words response contract stays compatible with older clients. Append-only continuation is only emitted after a word boundary; unfinished tokens use whole-token replacements.
- Android suppresses append-only suffixes for unfinished tokens, validates decomposed Hangul prefixes, and retains the earlier draft. Its candidate parser also accepts bounded short phrases for compatible future services.
- Only the last successful pair is cached in memory. Extending the same token with unchanged preceding context immediately reuses matching alternatives at the NEW edit version. If both remain valid, no new API request is sent. Other context/word changes and closing/disabling clear or bypass reuse. Nothing is persisted or shared across users.
- Same GPT-5.4 model, API key location, signed authentication, consent policy and 350 ms typing debounce. This reduces repeated waits/requests; it does not promise that the first provider request is faster.
- Gaze/calibration/scrolling changes from v38 are unchanged.

Tests cover full-token replacement, retaining preceding text, rejecting suffix-only results, complete alternatives, current-version reuse and invalidating reuse after context changes. Server tests verify request instructions and deterministic suppression of unfinished-token continuation.

## Production smoke check

Production deployment `dpl_5Ypnmeod3X59GSqFyNGb4vrVGUL8` is aliased to https://notouch-server.vercel.app. A signed request with synthetic draft `도` returned HTTP 200 and `words: ["도와줘", "도와줄게"], continuation: ""` in 1,837 ms on this run. A replay was rejected with 409. This is one observation, not a latency guarantee or before/after benchmark. Existing clients benefit from the server prompt; v39 is needed for immediate in-memory response reuse.

## Final validation and artifact

- Android `testDebugUnitTest`, `assembleDebug`, `lintDebug`: successful; 189 unit tests, zero failures/errors.
- Node relay tests: 20 passed. Production smoke result above.
- APK v2 signature verified, versionCode 39, arm64-v8a.
- File: `output/apk/NoTouch-v39-S23-texting-completions-debug.apk`.
- SHA256: `6f3d9eaacdedddc34acc36e20e40bc534aa93eda7b8eac0e32156fac277ac7ff`.
- Drive: https://drive.google.com/file/d/1q-E9DN9pN0URI6CyeqXePzQhguzK0MAe/view
- No physical Android device test was performed in this session.
