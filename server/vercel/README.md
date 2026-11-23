> 2026-11-23 owner-requested update: prediction count caps are disabled by default (config `limits_enabled:false`). No per-installation, IP, minute or day count blocks texting. Proof verification, Redis replay checks, request size limits and provider timeouts remain. Provider billing/rate limits still apply. Set server environment `NOTOUCH_PREDICTION_LIMITS=on` and redeploy to restore the historical quotas below. The separate legacy analyze endpoint is unchanged. Existing apps must reload the online-prediction policy and consent again; no APK rebuild is required.

# Text-entry production relay (v2)

Production base URL: https://notouch-server.vercel.app

The Android texting client uses GET /v2/config and POST /v2/predictions. It creates a non-exportable EC P-256 private key in Android Keystore on first prediction. No provider key, shared app token, or login credentials are included in the APK. The public URL defaults in Gradle; online-prediction.properties may override it.

## Authentication and data flow

1. GET config and obtain explicit existing UI consent for its policy_id.
2. Serialize the draft JSON once as UTF-8. Sign SHA256withECDSA (ASN.1 DER signature) over:
   `POST\n/v2/predictions\n{epochMilliseconds}\n{nonceHex}\n{sha256RawBodyHex}`.
3. Send X-NoTouch-Key (SPKI DER, base64url without padding), X-NoTouch-Time, X-NoTouch-Nonce (16 random bytes, hex), X-NoTouch-Signature (base64url).
4. Server validates P-256 signature, exact bytes, 60-second clock window, draft length and consent. Redis atomically rejects replay and reserves installation/IP quotas before the existing shared quota and provider call.
5. Only the current NoTouch draft goes to the fixed OpenAI Responses endpoint, model gpt-5.4, store:false, strict output schema. App rejects obsolete draft versions. Provider failures do not erase the draft or disable direct typing.

This is **anonymous installation proof, not official-app attestation**. An attacker can generate their own key. Global quotas bound provider call volume; they do not guarantee availability against abuse or a fixed dollar budget. For wider deployment use account-based allowances and/or platform-compatible app attestation plus operational abuse monitoring. No claim is made that One Store verified the app.

## Limits and retention

Installation: 6/minute, 50/UTC day. Trusted platform IP: 12/minute, 100/UTC day. Global across v1 and v2: 12/minute, 200/UTC day. Global model output cap: 512 tokens per prediction. Max draft 2000 characters, request body 16 KiB. A valid signed retry requires a new nonce; the app does not automatically retry paid predictions.

Nonce TTL 180 seconds. Installation identifiers and daily-salted IP hashes/counters expire within 48 hours. These are pseudonymous abuse-control records, not guaranteed anonymous data. Application code does not log drafts or full upstream errors. Vercel/provider infrastructure has its own logging policies. OpenAI's default abuse-monitoring retention may be up to 30 days.

## Deployment

Deploy this directory to the existing notouch-server Vercel project. Node 22. Set OPENAI_API_KEY and existing NOTOUCH2_KV_REST_API_URL / NOTOUCH2_KV_REST_API_TOKEN (UPSTASH/KV aliases supported). No secret needs to be passed to Android developers. Legacy /v1 and /api/analyze retain their NOTOUCH_TOKEN_SHA256 shared-token authentication for backwards compatibility; v2 never accepts that token as a substitute for a signature.

Run `node --test`. Redis failure denies prediction before any billable provider call. Configure time automatically on Android; clock skew beyond 60 seconds fails authentication. Server maxDuration is 30 seconds, provider deadline 18 seconds, app overall deadline 30 seconds. Direct input remains usable offline or at quota exhaustion.

## Store handoff

Use the team's existing release signing key and package identity. This repository's generated debug APK is for testing, not store upload. Keep versionCode greater than the store's previously accepted version (this change uses 31; verify against the store console). Update store privacy disclosure to match /v2/config, including OpenAI draft processing and abuse-control identifiers. Store review and physical-device testing are separate from automated checks.
