# Vendor incoming paid orders — verification, 2026-11-09

Foreground incoming orders and server response deadlines are implemented and committed. Final verification: 58 API regressions, 83 Vendor host tests, and eight native checks pass. Closed/locked-app push and physical-phone audibility remain open. The sanitized [verification receipt](../evidence/vendor-incoming-orders-2026-11-09/verification.json) contains exact files, artifacts, cases and server readbacks; no screenshots or credentials are committed.

## Scope and contract evidence

The signed PDF, page 3, point 30, requires preparation within a reasonable time and indication that a parcel is ready. Page 12, points 118–119 cover receiving/preparing orders and marking them ready for SEQUO pickup. These clauses do not prescribe a ringtone, a 45-second alert or a 10-minute cancellation policy.

The foreground call-like alert, Accept/Refuse controls and unanswered-order cancellation implement the user's follow-up request. The configurable 45-second/10-minute defaults are operating choices made under that request, not quoted contract deadlines. Existing preparation/ready workflows, merchant scope and paid-payment guards remain in use.

## Resulting behavior

- Only a newly persisted, verified-paid order opts its new merchant parts into response timing. Legacy rows are not backfilled; an admin repair/create cannot opt an old parent into automatic cancellation.
- A new scoped order may alert for at most 45 seconds. Dismissal/unanswered alert stops ringing while the order remains in the pending queue until its server deadline. Refresh/relaunch does not intentionally re-ring a recorded order.
- Existing Accept and Refuse routes are used. Successful server readback controls the result; an uncertain write remains retryable rather than presenting fake success.
- At or after the response deadline, pending acceptance fails and records expiry atomically. Acceptance/refusal/expiry share parent-before-part locks. Accepted or rejected parts are not subsequently expired.
- A timely refusal of a NEW timed part records REJECTED. A timeout records EXPIRED/CANCELLED. Same-reason refusal retries preserve the canonical timestamp/event; changed reasons cannot overwrite it.
- If all merchant parts are unconfirmed and there is no mission, refusal/expiry cancels the parent and other still-pending parts. If work is confirmed or a mission exists, confirmed work is preserved and SEQUO operations review is recorded.
- Further readiness mission creation is blocked for a review-required order. Existing mission continuation is preserved; no blanket courier cancellation, substitute merchant or partial-fulfillment policy was invented.
- Cancellation is not a refund: payment remains Validated, completed checkout evidence remains intact, and customer status explicitly avoids a refund claim. No provider/refund/payout call or stock movement was added. Current checkout has no stock reservation to restore.

## Server timing, migration and configuration

V62 adds nullable timing/disposition columns without changing existing records. It also extends the existing order-event check to MERCHANT_RESPONSE_EXPIRED and MERCHANT_RESPONSE_REJECTED while retaining all three previous event types; applied V10 was not edited.

Existing list/detail/action response shapes are retained. Additive fields: createdAt, alertUntilAt, responseDeadlineAt, responseExpiredAt, responseState, responseDisposition and serverNow. The compatibility alias responseExpiryDisposition is populated only for expiry. Existing sellerResponseDueAt/SLA reads prefer the new deadline when present; untimed legacy SLA behavior remains unchanged.

| Property under sequo.merchant-fulfillment.response-deadline | Default/effect |
| --- | --- |
| enabled | true; opt future canonical paid orders into snapshots |
| response-window | PT10M |
| alert-window | PT45S; positive, at most 45 seconds, and no longer than response-window |
| scheduler.enabled | true; independent server enforcement while apps are closed |
| scheduler.fixed-delay-ms | 15,000; due rows are rechecked under locks |

Changing a default does not rewrite stored deadlines. The scheduler scans bounded eligible paid/active candidates and commits one parent at a time; closed records cannot starve later eligible orders. Tests disable automatic scheduling and invoke enforcement explicitly.

AdminOperations may call POST /api/merchant/sub-orders/response-deadlines/enforce with optional {"limit":100}, maximum 200. Client time is not accepted as enforcement authority. The actual authenticated manual actor is recorded; normal merchants/customers cannot invoke it.

Local PostgreSQL migration 61 → 62 and schema validation: PASS. Final local workspace restart: PASS, with existing data retained. The first unanswered order M4X-QDJ-65Y had deadline `2026-11-09T02:13:47.174019Z` and was cancelled by the scheduler at `02:13:59.520978Z`, 12.347 seconds later. Both apps were out of the foreground. Its exact expiry timestamp, CANCELLED parent and Validated payment survived the final server restart; accepted/refused states from both phones survived too. Scheduling delay is normally up to the next 15-second scan while the server is running, not a strict delivery SLA.

## Notifications and local-only demonstration

New paid parts publish the existing ORDER_CREATED workflow event after commit. Recipients are active scoped owners/staff with active memberships/accounts and an active merchant; merchant IDs are not treated as login user IDs. Bounded payloads use allowed sequo:// links. Real scoped inbox reads, inactive-recipient exclusions and cross-merchant denial are tested.

The foreground coordinator uses server timing, current paid/active state, workspace/account/merchant-scoped durable receipts and effective notification preferences. Native playback is implemented with a finite timer, stops on background/disposal and does not change system volume or bypass silent/DND settings. iOS uses ordinary foreground audio/haptics; Android adds VIBRATE permission only. No CallKit/VoIP/background-audio service was introduced.

Background/closed-app push delivery remains UNCONFIGURED: native FCM/APNs token acquisition/registration and provider setup were not completed here. Foreground polling/ringing does not prove wake-from-closed behavior. Physical audibility/haptics remain UNVERIFIED.

A separate guarded local-only endpoint, POST /api/admin/local-fixtures/vendor-incoming/{scene}, supports fixed platform/intent/attempt scenes. It reuses pending checkout, fixed LOCAL_FIXTURE evidence and normal reconciliation; it accepts no arbitrary payment amount, identity or success toggle, preserves the original manifest and never resets handled orders. Production simulation guards remain unchanged.

## Automated verification

API: 58 tests PASS, 0 failures/errors/skips; main/test compilation PASS; 44 seconds. Run from sequo-api:

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew --offline --max-workers=1 -Dorg.gradle.jvmargs=-Xmx512m test \
  --tests '*MerchantResponseDeadlineHttpTest' --tests '*MerchantResponseDeadlinePolicyTest' \
  --tests '*LocalMerchantIncomingFixtureHttpTest' --tests '*MerchantFulfillmentServiceTest' \
  --tests '*MerchantFulfillmentControllerTest' --tests '*MerchantFulfillmentSlaTest' \
  --tests '*MerchantFulfillmentNotificationTest' --tests '*DeliveryReadinessDispatchServiceTest' \
  --tests '*CustomerCartCheckoutHttpTest' --tests '*H2V51FlywayCompatibilityTest'
```

Coverage: 18 new deadline/refusal/policy cases; 3 fresh-fixture cases; existing merchant transitions/notifications, authoritative cart checkout, dispatch and migration checks. Boundary timing, legacy preservation, whole/partial cancellation, canonical refusal replay/actor, accept-versus-timeout/refusal and different-merchant races, scan starvation, HTTP authorization, actual inbox recipients and no-refund truth pass.

A later configuration-only follow-up caps alert-window at the shared native 45-second maximum, including a 45s+1ns rejection and an independent shorter-response bound. The same policy class reran PASS 3/3, 0 failures/errors/skips in 21s: `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew --offline --max-workers=1 -Dorg.gradle.jvmargs=-Xmx512m test --tests '*MerchantResponseDeadlinePolicyTest'`. Log: /tmp/sequo-merchant-response-policy-cap-verification.log. This is a targeted rerun of three existing methods, not three additional unique tests. Committed as `d03e2aa`.

Log: /tmp/sequo-merchant-response-backend-verification.log. The migration check and dynamic serverNow exposed during initial runs were corrected; canonical replay checks still compare every business field, excluding only the deliberate read-time clock. No auth guard or status assertion was relaxed.

Vendor: 83 host tests PASS, 0 failures/errors/skips; Android/iOS main compilers PASS; final check 27 seconds. Reproduce from SequoVendor:

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew --offline \
  -PsequoApiBaseUrl=http://10.0.2.2:55840 \
  -PsequoReleaseId=sequo-vendor-incoming-20261109-01 -PsequoEnvironment=LOCAL_FIXTURE \
  :shared:testAndroidHostTest :shared:compileAndroidMain :shared:compileKotlinIosSimulatorArm64
```

Log: /tmp/sequo-vendor-incoming-final-check.log; test XML: SequoVendor/shared/build/test-results/testAndroidHostTest. Android APK and signed simulator-app builds passed. This proves packaging/compilation, not physical audibility. The actual UI outcomes are below.

## Commits and changed scope

- API bb50bba: domain timing, response policy/service/controller, merchant DTO/actions/repository/entity, readiness-dispatch guard, order history/customer projection, V62/configuration and focused/regression tests (14 files).
- API d86758e: guarded LocalMerchantIncomingFixture, fixed journey wrapper and three HTTP regressions (3 files). 
- API d03e2aa: reject alert configuration above the native 45-second ceiling; focused policy regressions.
- API 5b233c9: isolated Android incoming/read-only queue checks and guarded launcher.
- API 942d2f0: isolated iPhone checks, prepared-bundle provenance and safe original-suite selection. The final running local API was rebuilt from this revision.
- Vendor 19fff4e: finite Android/iOS alerts, scoped native receipts, coordinator/models/DTOs and tests.
- Vendor 5ef6659: shared incoming-order dialog, existing Accept/Refuse integration, polling/lifecycle cleanup and EN/FR resources.
- Vendor c9fcd64: wait for/recheck effective notification preferences before playing sound. Current Vendor HEAD: c9fcd64.

Original Vendor Xcode project edits were preserved; no identity, payment/provider configuration or app role was changed by these UI/platform commits.

## Native acceptance

All tests use the real installed LOCAL_FIXTURE app, a normal authorized Yawa session, newly paid synthetic records and actual server actions. No app-data reset, session injection or real provider operation was used. Each of the following runs executed exactly one test with zero failures/errors/skips in its final result.

| Check | Android API36 emulator | iPhone 16 / iOS18.6 simulator |
| --- | --- | --- |
| Incoming reference, actual quantity 2 and server subtotal 9,600 CFA | PASS | PASS |
| Accept; canonical ACCEPTED readback and preparation action | PASS · MD6-KKR-D3V | PASS · M7B-C4J-R7R |
| Refuse; canonical REJECTED, parent CANCELLED, payment Validated | PASS · M0N-18T-C47 | PASS · MA6-YPC-0M8 |
| Unanswered alert closes; exact part remains waiting | PASS · M9A-TJ0-NYF | PASS · M6Q-BDE-8YF |
| Separate existing waiting-queue read without a business action | PASS · M0X-4NW-SMQ | PASS · M9A-TJ0-NYF |
| Accepted/refused/expired outcomes persist after server restart | PASS, independent API readback | PASS, independent API readback |
| Closed/locked-app ringing | UNCONFIGURED | UNCONFIGURED |
| Actual physical-phone volume, vibration and silent/DND behavior | UNVERIFIED | UNVERIFIED |

The shared ten-minute scheduler was exercised on a real unanswered local order as recorded above; a separate ten-minute iPhone wait was not needed or claimed. Native receipt/relaunch, stale reads, paid/merchant eligibility, FIFO windows, clock drift/latency, failure/disposal and finite playback are covered by the host tests. Native tests finish through the ordinary Home action; they do not claim microphone measurement or hardware vibration proof. The Android emulator audio HAL reported I/O errors, so audible sound is explicitly unverified.

Android official JUnit evidence is preserved under `/tmp/sequo-vendor-incoming-devices/*-junit.xml`; final connected logs are `/tmp/sequo-vendor-incoming-android-accept-2-as-accept.log`, `android-refuse-1-as-refuse.log`, `android-accept-3-as-wait.log` and `android-waiting-diagnostic-final.log` (all under `/tmp/sequo-vendor-incoming-`). iPhone results and summaries are under `/tmp/sequo-vendor-incoming-devices/ios-ui-02/case-*/VendorIncomingOrder.xcresult` and `test-summary.json`. The [Android instructions](../../scripts/device-reference-tests/android/INCOMING_ORDER_TEST.md) and [iPhone instructions](../../scripts/device-reference-tests/ios/INCOMING_ORDER_TEST.md) record exact guarded commands.

Earlier native runs exposed test-harness issues: rapidly replaced Compose nodes, navigation labels without exported clickable flags, and an overly early iPhone auth probe. These were corrected using the existing proven label/node behavior; final actual button and queue tests pass. Guarded failure captures were used locally to inspect the real screen and remain outside Git. Existing iOS-runner unit checks also pass 14/14 (`python3 -B scripts/device-reference-tests/ios/test_run.py`).

Both installed apps are release `sequo-vendor-incoming-20261109-01`, source `c9fcd64`:

- Android APK SHA-256: `1b30b709e6b6acf015198abae76e107598cc32eba89d7635d2a74100a2828ba5`. The installed package was pulled read-only and its hash matched exactly.
- iPhone simulator full-payload SHA-256: `518cf6e20c9aa665cd3373a22eeda689e61dbcc653e9d8477fc713d46d7d066a`. Every test launcher compares the complete installed payload and debug binary hashes with the final signed simulator artifact before running.
- Artifacts and build-target metadata: `/tmp/sequo-vendor-incoming-devices/vendor-android` and `vendor-ios`. These are local fixture artifacts, not a production deployment or a physical-iPhone IPA.
- Original Vendor Xcode project SHA-256 remains `a7f7b50149460a7df096ea86593ca2644a0d463d0add8e59f42cd0c1c16e3af9`. Its pre-existing edit and unrelated untracked notes were not staged.

## Reproduction and remaining owners

Start the ordinary retained workspace from `sequo-admin` with `npm run dev:local -- --no-browser --with-fixtures`. Keep providers disconnected. Build/install the identified Vendor fixture package with the existing `scripts/build-acceptance.py` workflow, then sign into the normal Yawa local account. Prepare the native test bundle BEFORE creating a timed record. Through an authenticated guarded local development admin session, POST `/api/admin/local-fixtures/vendor-incoming/{scene}` with exactly `{"attempt":1}` (or 2/3). Scene values are `android-accept`, `android-refuse`, `android-wait`, `ios-accept`, `ios-refuse`, `ios-wait`. Only unused fixed slots create a new alert; retries preserve existing records and deadlines. Supply that response's actual `subOrder.publicReference` and `itemSubtotalCfa` to the explicit native test command. The 18-slot bound is intentional; no old scene was reset to obtain a passing result. Android acceptance slots 1 and 3 were used for unanswered checks because all fixed scenes create the same initial paid-pending state.

| Open item | Owner | Dependency / next action |
| --- | --- | --- |
| Closed/locked-app arrival alert | Developer + SEQUO notification-service owner | Connect native push-token registration and the server transport using authorized configuration; implement/verify the appropriate ordinary notification experience on both platforms. Current foreground checks are not proof of closed-app delivery. |
| Physical sound/vibration/settings | Tester | Use properly signed physical-device builds and synthetic accounts. With Vendor open, deliver a fresh paid test order; test normal volume, silent/DND, disabled preferences, Home/background/logout and finite stop. The simulator app cannot be installed as a physical-iPhone build. |
| Cancelled paid-order refunds and partially confirmed groups | SEQUO operations/business owner | Decide and execute the approved operational/refund treatment. The feature preserves the verified payment, records review where confirmed work exists and never claims a transfer/refund occurred. Live provider gates remain off. |
| Existing untimed active orders | SEQUO operations + developer | Existing SLA/history remains unchanged. Any migration to the new cancellation policy requires an explicit separately approved treatment; do not backfill silently. |

Next work can depend on the additive timing/status contract, immutable new-order deadlines, scoped inbox intent, foreground native controls, canonical responses and payment preservation. It must not assume background push, physical audibility, automatic refunds or production rollout is complete.
