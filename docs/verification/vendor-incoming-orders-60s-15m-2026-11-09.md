# Vendor timing selected by the user — 2026-11-09

This update applies the user's choice of **60 seconds of ringing and 15 minutes total to confirm**, measured from creation of the verified-paid merchant part. After the ring window ends, approximately 14 minutes remain to accept a waiting order. Accept/refuse actions, role checks, payment preservation and partial-order review behavior are unchanged.

This supersedes the initial 45-second/10-minute defaults. The [previous verification](vendor-incoming-orders-2026-11-09.md) and its original JSON receipt remain historical evidence; their results and timestamps were not rewritten.

## Effective policy

| Setting | New-order value |
| --- | --- |
| `sequo.merchant-fulfillment.response-deadline.alert-window` | `PT60S` |
| `sequo.merchant-fulfillment.response-deadline.response-window` | `PT15M` |
| Native maximum foreground sound window | 60,000 ms |
| Server enforcement scan | 15 seconds, unchanged |

Environment overrides remain supported through `MERCHANT_INCOMING_ALERT_WINDOW` and `MERCHANT_RESPONSE_WINDOW`. Configuration rejects a ring window above 60 seconds, nonpositive durations, or an alert longer than the whole response window. The client uses the remaining time from server snapshots; it does not invent or extend deadlines.

Stored deadlines are immutable: existing timed orders retain their 45-second/10-minute snapshots, and untimed legacy orders stay untimed. No migration, backfill, reset or rearming was introduced. Local readback of accepted order MD6-KKR-D3V confirms its original 45/600-second windows and state remain unchanged after the updated server restart.

## Checks

- API: **37/37 pass**, zero failures/errors/skips. Includes before/at/after the new response boundary, replay/concurrency/scope checks, legacy timed snapshot preservation, fixed local fixtures and existing checkout flows. Final run: 23 seconds. One stale fixture-test assertion was updated after the initial 36/37 run; no runtime behavior or history was relaxed to obtain a pass.
- Vendor: **85/85 host tests pass**, zero failures/errors/skips; Android and iPhone Simulator main compilation pass. Includes exact 60-second audio/presentation boundaries, 15-minute waiting boundary, shorter server windows and explicit preservation of old 45-second/10-minute records. Final run: 23 seconds.
- Existing iOS test-runner unit checks: **14/14 pass**.
- Native timing checks: **4/4 pass**: a full unanswered alert and a separate existing waiting-queue read on each platform. New Android order **MXQ-TZK-0GY** and iPhone order **MKA-T6K-ERF** have exact 60/900-second snapshots and remain pending after the ring stops. The first Android attempt encountered a normal session-restore/auth-chooser race in the test harness; its startup check was corrected and the final complete flow passed. No production auth path was changed. The sanitized [receipt](../evidence/vendor-incoming-orders-2026-11-09/verification-60s-15m.json) records final results, readbacks and artifact hashes.

API command, from `sequo-api`:

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew --offline --max-workers=1 -Dorg.gradle.jvmargs=-Xmx512m test \
  --tests '*MerchantResponseDeadlinePolicyTest' --tests '*MerchantResponseDeadlineHttpTest' \
  --tests '*LocalMerchantIncomingFixtureHttpTest' --tests '*CustomerCartCheckoutHttpTest'
```

Vendor command, from `SequoVendor`:

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew --offline \
  -PsequoApiBaseUrl=http://10.0.2.2:55840 \
  -PsequoReleaseId=sequo-vendor-incoming-20261109-02 -PsequoEnvironment=LOCAL_FIXTURE \
  :shared:testAndroidHostTest :shared:compileAndroidMain :shared:compileKotlinIosSimulatorArm64
```

Logs: `/tmp/sequo-vendor-timing-60-15-api-check.log`, `/tmp/sequo-vendor-timing-60-15-vendor-check.log`, and `/tmp/sequo-vendor-timing-60-15-runner-unit.log`. Native commands remain in the guarded [Android](../../scripts/device-reference-tests/android/INCOMING_ORDER_TEST.md) and [iPhone](../../scripts/device-reference-tests/ios/INCOMING_ORDER_TEST.md) instructions. Prepare the test bundle before creating a fresh paid fixture. Existing fixed slots are never reset.

## Builds and commits

- Vendor `79ed43c`: native 60-second limit, current documentation, updated boundaries and old-window regressions.
- API `62748e8`: selected 60-second/15-minute defaults and server/fixture regressions.
- Both updated installed fixture builds identify release `sequo-vendor-incoming-20261109-02`, source `79ed43c`. Artifacts and native results are under `/tmp/sequo-vendor-timing-60-15-devices`; the earlier cohort remains separate.
- The local server was rebuilt/restarted with the new defaults and its original database retained. The original Vendor Xcode edit remains unchanged; unrelated notes are uncommitted. No screenshots, signing credentials or tokens are included in the handoff.

## Scope limits

Foreground/resumed alerting remains the implemented scope. Closed/locked-app push registration and transport are still unconfigured; actual physical-phone audibility, vibration, silent/DND and volume remain unverified. The timer change does not enable real payments, refunds or production rollout. Cancellation continues to preserve verified-payment truth, and confirmed grouped work requires operations review.

The new 15-minute boundary is exercised with deterministic server tests; a separate 15-minute wall-clock wait on each phone is not claimed. The previous ten-minute live cancellation is historical evidence for the unchanged scheduler mechanism, not proof of a newly elapsed fifteen-minute interval.
