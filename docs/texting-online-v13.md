# Texting online v13

Includes texting 68340c4 + personal-model integration 1506ccc.

Production: https://notouch-server.vercel.app
- Existing production OPENAI_API_KEY reused without retrieving or changing its secret value. The separately supplied chat key is not used.
- Added /v1/config and /v1/predictions; preserved /api/analyze.
- Replaced uninstalled Redis integration with notouch-limits-v2, free tier, autoUpgrade=false, prodPack=false. Production reads NOTOUCH2_KV_REST_API_*.
- Shared atomic limits: 12 requests/minute, 200/day, including old analysis calls.
- Android includes only proxy URL and a limited shared team access token (not the OpenAI key). This token is extractable from a debug APK; the APK is for team testing, not public distribution. Revoke by rotating NOTOUCH_TOKEN_SHA256 and rebuilding.
- Server owns model gpt-5.4, store:false, no draft logging. Online prediction requires existing in-app consent. Response timeout raised to 25 seconds, provider budget 18 seconds. Direct typing remains available on failure.

Validation on 2026-11-16: 90 Android JVM tests + 12 Node tests passed; assembleDebug and lintDebug succeeded; APK signature verified. Real production prediction returned HTTP 200 in 3.06 seconds using the existing Vercel key. Physical phone behavior has not been verified.

Artifact: output/apk/NoTouch-texting-personal-online-v13-debug.apk
Version code 13; version 0.9.1-texting-online.
Deployment source is server/vercel. Deploy from that directory, not server (which retains the original Python implementation).
Local online-prediction.properties holds the team token and must not be committed. Provider secrets remain only in Vercel.
