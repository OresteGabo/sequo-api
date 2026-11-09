# Native app review — 8 November 2026

**Local review completed within the explicit device/provider limits below. All four iOS apps were opened individually; all eight final Android/iOS builds passed.** This is a local fixture review, not a production release or a recorded delivery for contractual acceptance. Build success, source review and actual device interaction are recorded separately.

## Environment and boundaries

- Dedicated local API `http://127.0.0.1:55840`, admin `http://127.0.0.1:5173`, retained PostgreSQL database `sequo_admin_dev` on loopback port 55924. Running API source is `6c2d2e1`; local practice services were left running with provider execution disabled. No production deployment or personal device was used.
- Current review ID `sequo-native-review-20261108 / LOCAL_FIXTURE`; iOS uses loopback and Android uses `http://10.0.2.2:55840`. These outputs are separate from the frozen Task 28 release candidate.
- Task-owned iPhone 16 simulator, iOS 18.6: `59D90F12-C368-449B-8752-6BD86241E8DB`. Android Medium Phone API 36.1 instance: `emulator-5580`.
- Native practice access uses eight fixed installed synthetic accounts, normal revocable device-bound tokens and persisted role/merchant/relay membership. See [DEVELOPMENT_MOBILE_ACCESS.md](DEVELOPMENT_MOBILE_ACCESS.md). It is disabled by default and independently requires explicit local environment, isolated database/JWT issuer/audience, direct loopback peer/Host, no forwarded headers, disabled provider execution and workers. Exact emulator Host support does not weaken production auth.
- No real payment, refund, payout, notification-provider delivery, physical locker action, identity-image collection or account-deletion execution is claimed. Fixture sign-in is separate from external Google/Apple OAuth verification.

## Actual native screen observations

Only the designated native UI reviewer operated native UI. Source/build evidence is listed separately; the authorized live API dispatch changes are identified explicitly below rather than treated as native UI observations.

| App | Observed facts | Remaining device evidence |
| --- | --- | --- |
| Sequo / iOS | Final `dc53ca6` installed; Ama's protected session restored. `Gourde` now returns 1 real product under the correct Maison merchant, without invented distance/rating. Detail shows its own stored product imagery, persisted description and 4 800 CFA. Real basket Add succeeded and quantity increased 1→2, with item amount 9 600 CFA. Home quote truthfully requests an address; eligible TEST Tokoin relay is listed. Notifications was reached: latest-server-record view (limit 100), 0 unread, genuine Aucune notification empty Inbox plus Archives/Refresh/View orders controls; no sample rows or provider-push claim. Earlier baseline also observed home, markets, persisted pending/failed/return orders, account and French/theme survival; plaintext `auth_session` was absent. | After process restart, the same basket quantity 2 / 9 600 CFA restored successfully. CUA scrolling again did not move the viewport, so below-fold controls remain unverified. View orders from Notifications loaded the real failed/pending/return-requested records; expanding the failed order showed its synthetic home destination and quantity 2. No new customer checkout or provider payment was executed. |
| Sequo / Android | Final `dc53ca6` APK cold-started successfully on emulator-5580; process stayed alive and AndroidRuntime/libc fatal output was empty. | Native visual/all-screen interaction remains unverified because the emulator window is not exposed through the available native CUA app surface. No alternate input automation replaced missing visual evidence. |
| SequoVendor / iOS | On signed source `0e0c846`, saved Yawa restore succeeded; compact cards show readable Setup needed status. Normal UI moved only order `SQ-local-ops-v1-merchant-preparing` from PREPARING through Mark ready; canonical counts Preparing 1→0 and Ready 4→5. Mark closed persisted Paused / Orders paused, then Mark open persisted Open / Open for orders; Yawa was left OPEN with no invented hours. Normal protected login as Mawuli showed Catalog 1/1 (Lot de trois carnets), versus Yawa 3/3 (including Gourde/Bowl), with no old Yawa editor. | Final `54db35b` installed: Mawuli protected restore showed scoped dashboard 0 pending / 0 accepted / 0 preparing / 1 ready. Account Sign out returned to clean login without stale remembered-merchant Continue. No external provider login or terms acceptance was performed. Activity sections were not fully scrolled/read and are not claimed verified. |
| SequoHub / iOS | `1e0030a` installed for the auth/negative-pickup retest; Dede's protected Tokoin session restored. Final return custody history shows readable localized cards/dates with actual actor/details. Logout shows Google/local-fixture auth only; unsupported Apple/email buttons are absent. Esso login shows only Adidogomé's 3 occupied slots and own parcels. On the after-eight-days parcel, an invalid fixture PIN with identity unchecked kept Release disabled; toggling the test checkbox enabled it, explicit confirmation was refused by the server with a friendly error and no false unassigned/empty state. Refresh recovered 3 occupied slots and the unchanged Delayed parcel. All 4 tabs were reached; Settings showed actual Esso/site/preferences without saving. Returns was genuinely empty for that site, showed ID/PIN/optional condition and disabled physical receipt while blank, with explicit no-refund-approval copy. | No settings write, return intake, actual release, QR camera or physical identity/handoff check was performed. The later-proven list/detail history gap is fixed in `422545e` and retested: Esso secure restore now shows real Delay/Review/Deposit cards rather than Aucun historique. Three of 5 server events were visible in the viewport; all 5 are verified by API/host data, not by scrolling. Stored synthetic age-evaluation/no-approved-tariff/no-fee-assessed note opened in details. Earlier offline refresh cleared data and showed a French error plus an incorrect unassigned notice; final polish fixes the guard, but a final API-shutdown/offline rerun was not performed. |
| SequoRider / iOS | Signed `59b3478` restored Senam. Accept moved the target offer to Assigned/Accepted; blank pickup note was rejected without advance, TEST OK advanced to Collected, wrong PIN was rejected without advance, and the privately operator-issued synthetic PIN completed delivery. History showed 10 completed / 0 failed and the new target with a 400 CFA preview. All five tabs, compact real profile, localized navigation and clean sign-out were reached. Komlan then showed 2 own missions / 0 offers and a salaried empty/zero-transfer view, with no Senam earnings leakage. Final `a078aa6` restored Komlan securely; selecting French then restarting retained French and the correct account. Switching back to Senam and explicitly refreshing Earnings showed 4 000 CFA / 10 recorded missions / 0 provider paid, matching the synchronous ledger. | The first post-delivery 3 600 CFA / 9 screen was an old client snapshot; fresh read/Refresh matched the 4 000 CFA ledger. Actual journey proof belongs to `59b3478`; locale/reopen/earnings follow-up belongs to `a078aa6`. This staged journey does not verify physical delivery, external identity, GPS, camera/background behavior or money movement. |

Test screenshots are local artifacts and are no longer stored in this repository. The observations above and structured verification results below retain the Customer product/basket, Vendor dashboard/sign-out, Hub history/negative-pickup and Rider delivery/recorded-earnings checks. These checks do not imply financial-provider confirmation or that all five Hub API events were scrolled.

The observations above distinguish actual native interaction from source reachability and automated coverage.

Final Customer `dc53ca6`, Vendor `54db35b`, Hub `422545e` and Rider `a078aa6` Android cold-start smoke passed on emulator-5580: each final APK process remained alive with empty AndroidRuntime/libc fatal output. Android visual interaction remains unverified. [Durable Android smoke JSON](evidence/native-review-2026-11-08/android-startup-smoke.json) binds all four final APK source IDs and hashes, COLD results and uiVerified=false.

## Live API role/scope and dispatch evidence

The [sanitized live role/dispatch matrix](evidence/native-review-2026-11-08/live-role-dispatch-matrix.md) and [structured JSON](evidence/native-review-2026-11-08/live-role-dispatch-matrix.json) record **124 passing checks, 0 failures**, separate from host tests and native UI. The durable copy contains no token/password/PIN/secret/authorization fields. Normal guarded fixture sessions were logged out; no token files are part of the evidence.

The API checks cover allowed/denied role and ownership reads, public catalogue privacy, merchant isolation, self-only rider payout reads and operator site boundaries. Dede/Tokoin has 3 AVAILABLE slots, 0 parcels and 5 returns with 9 relay custody events; Esso/Adidogomé has 3 OCCUPIED slots and 3 parcels (Deposited, Delayed, ReturnToSellerReview). Parcel list omits history; authorized detail reads contain 5 actual events, exposing the native hydration gap corrected and retested in `422545e`. Customer Koffi has no persisted own order, so a positive own order-detail case was not invented.

After the native Yawa preparation action, normal authorized admin readiness dispatch/assign/offer routed the paid order `SQ-local-ops-v1-merchant-preparing` to Senam. Readback shows PACKED_READY merchant part and OFFERED_TO_COURIER mission; staff actor/time/idempotency history is preserved. This was an actual fixture backend mutation, not an inferred UI action. The existing merchant suborder DTO does not expose a persisted merchant actor, so that additional audit field is not claimed verified. No new payment, release, pickup, delivery, seed/reset or provider action was executed by that matrix.

## Verified source and automated checks

Current source checkpoints at report assembly:

| Repository | HEAD | Android host XML result |
| --- | --- | --- |
| Sequo | `dc53ca6837a3919a26976fd504e2d66463916660` | 49 tests / 14 suites; 0 failures, 0 errors, 0 skipped |
| SequoVendor | `54db35b647b70d9bcafc5e3684497f3c84d18c50` | 58 tests / 14 suites; 0 failures, 0 errors, 0 skipped |
| SequoHub | `422545e760b49af149bc2e49a72e113c2551ec2c` | 17 tests / 5 suites; 0 failures, 0 errors, 0 skipped |
| SequoRider | `a078aa6a14ba307fd59acf85c758feae9c9abc21` | 31 tests / 10 suites; 0 failures, 0 errors, 0 skipped |

The current XML snapshots total **155 passing Android host tests**, without adding earlier reruns to the count. Customer storefront, Vendor status/logout and Hub history/offline-empty/onboarding follow-ups are included in these latest host counts. Actual native evidence and remaining unverified controls are recorded separately above.

| Check | Exact evidence / result |
| --- | --- |
| Customer final Android host + app compile |49 passed, 23 seconds; `/tmp/sequo-customer-storefront-host-verification.log`. Includes 7 inbox, 6 search and 3 new actual catalogue-response mapping regressions plus existing tests. iOS main compile PASS 16 seconds, `/tmp/sequo-customer-storefront-ios-compile.log`. |
| Vendor final logout host suite |58 passed, 15 seconds; `/tmp/sequo-native-vendor-logout-host.log`. Includes 10 secure persistence/migration, 3 status presentation and 3 logout remembered-user regressions. |
| Customer identified Android + signed iOS |Android helper PASS 5 seconds, `/tmp/sequo-customer-storefront-android-build.log`; final native link PASS 21 seconds and Xcode BUILD SUCCEEDED, `/tmp/sequo-customer-storefront-signed-ios-build.log`. Source `dc53ca6`, per-platform generated origin and full payload hashes recorded below. |
| Vendor full signed logout artifact + Android |Native link PASS 29 seconds and Xcode BUILD SUCCEEDED; `/tmp/sequo-native-vendor-logout-ios-build.log`, source `54db35b`. Android helper PASS 13 seconds; `/tmp/sequo-native-vendor-final-android-build.log`. |
| Vendor iOS main compile |PASS, 26 seconds; `/tmp/sequo-native-vendor-secure-session-ios-compile.log`. |
| Hub final host suite |17 passed, 7 seconds; `/tmp/sequo-native-hub-history-host.log`. The latest regression omits history from list data, loads 5 actual detail events, rereads on refresh and fails closed for forbidden/missing/unavailable/wrong-ID/wrong-site details. Earlier 16-test UI log is `/tmp/sequo-native-hub-ui-host.log`. Three added chronology/history regressions retain original records, actors, notes and same-time events. Earlier 13-test assembly log `/tmp/sequo-hub-live-final-host-verification.log` remains a baseline. Covers scoped read failure, QR/PIN/identity intent, stable retry body, refresh, logout/account-switch races, explicit fixture appSource, saved preferences and dated opening-hours transport. Deletion intent is built/tested, not executed. |
| Hub final signed iOS + Android |Native compile/link PASS 29 seconds and Xcode BUILD SUCCEEDED; `/tmp/sequo-native-hub-history-ios-build.log`. Android PASS 3 seconds, source `422545e`, log `/tmp/sequo-native-hub-history-android-build.log`. |
| Rider final Android host suite |31 passed, 21 seconds; `/tmp/sequo-rider-language-host-verification.log`. Two new preference roundtrip/default cases; previous 29-test presentation run is `/tmp/sequo-rider-presentation-final-host-verification.log`. Earlier 27-test baseline is `/tmp/sequo-rider-screen-final-host-verification.log`. Includes fixture/session, error/next-step and checked-Keychain policy cases. |
| Rider iOS native compile/link |PASS; `/tmp/sequo-rider-screen-ios-compile.log`, `/tmp/sequo-rider-keychain-native-dictionary.log`. Final full signed wrapper and Android helpers PASS from `a078aa6`; `/tmp/sequo-native-rider-language-ios-build.log`, `/tmp/sequo-native-rider-language-android-build.log`. |
| Signing/payload helper policies |7 per app / 28 total passed; `python3 -m unittest discover -s scripts/tests -p test_acceptance_support.py -v`; `/tmp/sequo-native-build-signing-review.md`. |
| API native session baseline |9 focused HTTP/policy/unchanged admin-session checks passed, 36 seconds; `/tmp/sequo-native-fixture-auth-verification.log`. |
| API emulator/native guard + Hub scope |10 checks passed, 16 seconds; `/tmp/sequo-native-emulator-host-verification.log`. Includes persisted two-site locker reads, forged scope, anonymous/customer denials and authorized staff/empty reads. |
| API public catalogue storefront projection |19 tests passed, 33 seconds after a red reproducer; `/tmp/sequo-catalog-storefront-read-review.md`, commit `ededa2b`. Actual active merchant identity/published products remain visible without optional public metadata; inactive storefront suppresses fallback and private fields stay absent. |
| API canonical read-projection regressions |39 passed, 33 seconds; `/tmp/sequo-native-read-projection-tests.log`. Actual XML: AdminWebRead 2, DeliveryMissionController 14, DeliveryMissionService 10, OrderPersistence 9, Dispatch 3, one full home journey; 0 failures/errors. Source `fb9ac50`, `6c2d2e1`. |
| API final forwarded-header guard |3 checks passed, 36 seconds; `/tmp/sequo-native-forwarding-guard-verification.log`. |
| Local launcher |8 tests passed; `python3 scripts/local-workspace.test.py`, admin commit `9891c3d`. |

Every app's host command is run from its repository, using the cached JBR and explicit local target:

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew --offline :shared:testAndroidHostTest -PsequoApiBaseUrl=http://10.0.2.2:55840 -PsequoReleaseId=sequo-native-review-20261108 -PsequoEnvironment=LOCAL_FIXTURE
```

Rider's recorded final host command uses `http://127.0.0.1:55840` (host execution, not Android runtime). Vendor's separately recorded iOS main command substitutes that loopback URL and `:shared:compileKotlinIosSimulatorArm64`. Host XML is under each repository's `shared/build/test-results/testAndroidHostTest/`; source review does not replace native execution.

The [durable Rider journey readback](evidence/native-review-2026-11-08/rider-journey-readback.md) and [structured JSON](evidence/native-review-2026-11-08/rider-journey-readback.json) record an initial 33 passing GET-only checks identifying two stale read projections: customer-facing text still reflects initial checkout copy despite canonical DELIVERED, and raw mission GET packageCount differs from the real merchant suborder. Read-only projection fixes `fb9ac50` and `6c2d2e1` are committed with 39 passing focused tests; historical checkout copy/financial/custody records are preserved. After restart, the same durable evidence now adds 35 passing GET-only checks: both projections are resolved, canonical synchronous 400 CFA CourierPayable /4 000 CFA across 10 missions remains, and provider paid remains 0. Original observed discrepancies are retained with the follow-up rather than erased.

Reproduction of the verified 39-test API selection from `sequo-api`:

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew --offline test --tests '*AdminWebReadHttpTest' --tests '*DeliveryMissionControllerTest' --tests '*DeliveryMissionServiceTest' --tests '*OrderFulfillmentPersistenceServiceTest' --tests '*DeliveryReadinessDispatchServiceTest' --tests '*CustomerCartCheckoutHttpTest.homeJourneyUsesRealRoleAccountsExclusiveAcceptanceProofAndPersistedTracking'
```

## Corrected behavior and meaningful commits

| Repository | Commits | Concrete result |
| --- | --- | --- |
| Sequo |`305879d`, `a03849c`|Isolated real sessions and guarded practice entry; checked device-only Keychain migration; restore failures do not become a remembered fake account. |
| Sequo |`60e535a`, `1061d08`|Owner-scoped persisted notification inbox; catalogue search actually queries loaded server products and preserves IDs/prices/options. Fake Markets route card removed. |
| Sequo |`3c7c01a`, `63f71cd`|Simpler persisted order display and neutral destination; actual Gourde detail, search Add and basket quantity/restore retested. Below-fold product buying controls remain unverified. |
| Sequo |`dc53ca6`|Preserve nullable storefront distance/ETA/rating/location/status without 0 km/Available/default-fee claims. Real merchant name/product IDs remain visible/searchable; known values remain; unknown metadata chips are hidden and sorts are unknown-last. Live server quote remains unchanged. |
| SequoVendor |`ae554fa`|Serialized normal refresh avoids concurrent refresh-token replay; stable account/merchant workspace keeps drafts through token rotation and rejects stale account results. Correct Back priority and guarded real practice sign-in. |
| SequoVendor |`9c90dc1`|Checked device-only Keychain update/read/delete and exact-readback migration preserve supported legacy sessions without plaintext fallback. |
| SequoVendor |`54db35b`|Clear remembered-user quick-entry state on logout so an ended Yawa session does not leave a stale Continue Yawa prompt. 58 hosts and both artifacts passed; actual final Mawuli restore and clean sign-out retest succeeded. |
| SequoVendor |`0e0c846`|Readable localized parent status/payment labels, bounded card columns and 3 regression cases; 55 hosts and full signed replacement build passed. |
| SequoHub |`57faf18`, `d5a7325`|Real sessions no longer enter fake/sample repositories: actual assigned relays, logical slots, parcels, identity-confirmed pickup, return intake, persisted history/inbox, opening hours/dated exceptions/preferences/maintenance and deletion REQUEST. Session-generation barriers prevent refresh/result publication across logout/account changes. Explicit sample-data demo remains separate. |
| SequoHub |`422545e`|Hydrate each authorized listed parcel using its canonical detail response, validate exact ID/site and fail closed rather than inventing empty history. 17 hosts, both artifacts and actual Esso nonempty history retest passed (3/5 cards visible). |
| SequoHub |`1e0030a`|Failed refresh no longer also claims unassigned membership; readable localized complete custody history, scrolling truthful sign-in and unsupported provider controls hidden. 16 host checks and both artifacts passed; actual final restore/history/auth/scope and negative pickup were retested. |
| SequoHub |`1a73748`|Build guide distinguishes unsigned launch-only mode from protected simulator checks requiring validated development signing. |
| SequoRider |`0620e8f`, `ab0467b`, `246e1ff`|Verified scoped practice session and cancellation of old-account requests; accurate/localized mission labels and loaded counts, actual typed incident reason, next required custody step, denied/offline list clearing and disabled unavailable writes. Unsupported counts remain unknown. |
| SequoRider |`aaa149c`, `59b3478`|Observed responsive header/profile fixes, early custody controls, brand/navigation icons, localized actual route/mode and ETA, concise recorded-earnings copy. 29 hosts and final Android/signed iOS artifacts passed; native staged Senam journey and separate Komlan salary scope were exercised; physical/provider execution is not claimed. |
| SequoRider |`a078aa6`|Persist the existing EN/FR device UI preference with isolated Android preferences/iOS UserDefaults keys; no auth storage or new controls. 31 hosts and both artifacts passed; actual final French selection survived process restart with the protected Komlan session. |
| SequoRider |`938398c`|Preserved existing Keychain identity with checked native dictionary/query/read/write/delete and device-only accessibility. |
| All mobile helpers |Customer `281e011`; Vendor `4d8995e`; Hub `0692d45`; Rider `11786c9`|Validate an installed actual development certificate/team and hash full app payload/debug code. No fabricated identity or custom entitlement injection. |
| sequo-api |`c1aab11`, `39503fe`, `a134d3c`, `015ca81`|Explicit bounded native practice access, device binding, emulator Host guard and forwarded-request rejection. Normal auth/role boundaries remain enforced. |
| sequo-api |`887e320`|Actual persisted locker availability scoped to the authorized relay operator; no sample/SYNC locker IDs. |
| sequo-api |`ededa2b`|Project real active merchants lacking optional storefront rows, retaining unknown public metadata rather than omitting their published products. |
| sequo-api |`fb9ac50`, `6c2d2e1`|Derive known current customer/admin lifecycle status before stale checkout copy, preserving stored historical text. Raw mission GET/list/actions/dispatch-repeat now derive actual own package counts with legacy 0/final-consolidation 1 fallbacks and no home-address disclosure. 39 regressions passed; 35 post-restart GET checks confirmed both corrected fields and unchanged lifecycle/ledger evidence. |
| sequo-admin |`9891c3d`|Explicitly enable the bounded native practice capability only inside the isolated local launcher. |

All listed source follow-ups are committed. Automated checks/artifacts, final Rider locale restart, Hub history retest and post-restart API projections passed. The remaining device and operational dependencies below are explicit. No unsupported hardware/provider capability was added to make an action look successful.

## Artifact ledger and provenance

The current review artifacts live under `/tmp/sequo-native-review/`, not the immutable Task 28 bundle. Each `build-target.json` records origin, environment, release, source checkpoint and tracked dirty paths. Protected hashes are recorded where supported by that helper; Customer project SHA is bound independently in the preserved-work section because its current digest map is empty. A packaging helper's `installedOrLaunched:false` means the helper itself did neither; later native observations are separate evidence.

### Customer Android

- Artifact: `/private/tmp/sequo-native-review/customer-android/Sequo-sequo-native-review-20261108-LOCAL_FIXTURE.apk`.
- Metadata: `/tmp/sequo-native-review/customer-android/build-target.json`.
- Source: `dc53ca6837a3919a26976fd504e2d66463916660`; app ID `dev.orestegabo.sequo.fixture`.
- Signing: debug key.
- APK SHA256: `2f4188d301b9826efe6ef131babd39a29b9a16f7734c5275a12fb9a816b01cf3`.

### Customer iOS

- Artifact: `/private/tmp/sequo-native-review/customer-ios/Sequo-sequo-native-review-20261108-LOCAL_FIXTURE.app`.
- Metadata: `/tmp/sequo-native-review/customer-ios/build-target.json`.
- Source: `dc53ca6837a3919a26976fd504e2d66463916660`; app ID `dev.orestegabo.sequo.Sequo.fixture`.
- Signing: validated local Apple Development simulator.
- Full payload SHA256: `7d9ad3d0f974c807c7dedbd93d1ce3311a9d847bcf753f7ba54dcfde7c240b43`.
- Executable SHA256: `34107831cb441ca839d6546a7fb8dc5c991236ef59d6e1a963d5398f6241e9c8`.
- Debug code `Sequo.debug.dylib`: `f3d807a9c9fe19a005257dd6d8cda8a4ab42589b40f46e1d6052b28a7436eb10`.
- Final `dc53ca6` native retest verified Ama restore, Gourde/real merchant/detail, basket Add/quantity and the same product quantity 2 / 9 600 CFA after process restart. Below-fold controls remain unverified.

### Vendor Android

- Artifact: `/private/tmp/sequo-native-review/vendor-android/SequoVendor-sequo-native-review-20261108-LOCAL_FIXTURE.apk`.
- Metadata: `/tmp/sequo-native-review/vendor-android/build-target.json`.
- Source: `54db35b647b70d9bcafc5e3684497f3c84d18c50`; app ID `dev.orestegabo.sequovendor.fixture`.
- Signing: debug key.
- APK SHA256: `b0bde62a41f0410306e3bf9d7dab5c43a436c86759113bd67bd6c3827bfa4f70`.

### Vendor iOS

- Artifact: `/private/tmp/sequo-native-review/vendor-ios/SequoVendor-sequo-native-review-20261108-LOCAL_FIXTURE.app`.
- Metadata: `/tmp/sequo-native-review/vendor-ios/build-target.json`.
- Source: `54db35b647b70d9bcafc5e3684497f3c84d18c50`; app ID `dev.orestegabo.sequovendor.SequoVendor.fixture`.
- Signing: validated local Apple Development simulator.
- Full payload SHA256: `bb1718624787f4e2708d0fd807381924b3a5e457af23797fdd686b8db1ca492b`.
- Executable SHA256: `ed7f50f0825aa745f8aa8607d9dcfb30c9345688cb30d57087d5d33a3b17a31a`.
- Debug code `SequoVendor.debug.dylib`: `0627b0182cb705a1033a605720be8efa8dfc2407fe971924d4df641c0add277f`.
- Final `54db35b` native retest verified Mawuli protected restore/scoped dashboard and clean sign-out with no stale Continue. Preparation/store/catalog scope proof applies to earlier `0e0c846`.

### Hub Android

- Artifact: `/private/tmp/sequo-native-review/hub-android/SequoHub-sequo-native-review-20261108-LOCAL_FIXTURE.apk`.
- Metadata: `/tmp/sequo-native-review/hub-android/build-target.json`.
- Source: `422545e760b49af149bc2e49a72e113c2551ec2c`; app ID `dev.orestegabo.sequohub.fixture`.
- Signing: debug key.
- APK SHA256: `02dca080c8364047cce8bdfcae9192f4e85a1ddf3462caf5fb87457609f40178`.

### Hub iOS

- Artifact: `/private/tmp/sequo-native-review/hub-ios/SequoHub-sequo-native-review-20261108-LOCAL_FIXTURE.app`.
- Metadata: `/tmp/sequo-native-review/hub-ios/build-target.json`.
- Source: `422545e760b49af149bc2e49a72e113c2551ec2c`; app ID `dev.orestegabo.sequohub.SequoHub.fixture`.
- Signing: validated local Apple Development simulator.
- Full payload SHA256: `5bce3940d826cf884a730fb31cf3a5924cb1df7c132a529dfe1ef59e1c0d7cb3`.
- Executable SHA256: `f9f8fe4d53ad266cb8f221d0a9c9f25cc94542a36897923def59ef70f98bd009`.
- Debug code `SequoHub.debug.dylib`: `1be732cf289236c8ba846e4b6077622430fb025d9596f5e870860bcc231dad06`.
- Final `422545e` Esso secure restore/nonempty parcel history retest passed. Prior `1e0030a` covered return history/auth/settings/returns and negative identity/PIN pickup. Three of 5 events were visually reached, all 5 API/host events retained.

### Rider Android

- Artifact: `/private/tmp/sequo-native-review/rider-android/SequoRider-sequo-native-review-20261108-LOCAL_FIXTURE.apk`.
- Metadata: `/tmp/sequo-native-review/rider-android/build-target.json`.
- Source: `a078aa6a14ba307fd59acf85c758feae9c9abc21`; app ID `dev.orestegabo.sequorider.fixture`.
- Signing: debug key.
- APK SHA256: `298c50f4e2fe82eab7a70b1dc5f6c424d3938cf5d9787e6ca185878e9a1be549`.

### Rider iOS

- Artifact: `/private/tmp/sequo-native-review/rider-ios/SequoRider-sequo-native-review-20261108-LOCAL_FIXTURE.app`.
- Metadata: `/tmp/sequo-native-review/rider-ios/build-target.json`.
- Source: `a078aa6a14ba307fd59acf85c758feae9c9abc21`; app ID `dev.orestegabo.sequorider.SequoRider.fixture`.
- Signing: validated local Apple Development simulator.
- Full payload SHA256: `40e4ecd1af1327a351cbe19837108ba6c2bbac1802314f6660a5b937e91b28da`.
- Executable SHA256: `e7ba43aff577d679575957f9bfca8ffeddf731a40a8ac232470c52423eed40e5`.
- Debug code `SequoRider.debug.dylib`: `1c47054d6a6905207060463fd6b3b765b0284e40988ce7c14da9dd56ff379756`.
- The actual staged delivery journey used `59b3478`. Final `a078aa6` additionally verified Komlan secure restore, French selection surviving process restart, then clean sign-out/Senam sign-in and an explicit earnings Refresh showing 4 000 CFA across 10 recorded missions with 0 provider-paid.

### Reproducing an identified build

From each app repository, use the matching output directory (`customer`, `vendor`, `hub`, `rider`) and explicit target; cached offline tooling is required:

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' python3 scripts/build-acceptance.py --platform android --api-base-url http://10.0.2.2:55840 --release-id sequo-native-review-20261108 --environment LOCAL_FIXTURE --output-dir /tmp/sequo-native-review/APP-android
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' python3 scripts/build-acceptance.py --platform ios --api-base-url http://127.0.0.1:55840 --release-id sequo-native-review-20261108 --environment LOCAL_FIXTURE --output-dir /tmp/sequo-native-review/APP-ios --development-signing-reference /tmp/sequo-native-review/development-signing.json
```

The private signing reference is a local setup input, not supplied by a clone. The helper validates file ownership/privacy, a valid installed Apple Development certificate and its matching team, then uses standard Automatic Xcode signing. No private key is exported. Without this option, unsigned simulator builds are launch-only; they do not prove protected Keychain operations. Xcode debug launcher executable hashes can remain constant while Kotlin code changes: the full payload and `.debug.dylib` hashes are authoritative for these packaged apps.

Android uses separate `.fixture` application IDs; HTTP is limited to explicit LOCAL_FIXTURE debug builds. Nonlocal staging requires HTTPS. Ordinary production origins/signing defaults were preserved.

## Keychain and session verification limits

The independent unhosted native probe identified an actual malformed dictionary: the original bridge returned Security `errSecParam`(-50); a real CFDictionary builder reached the valid query's missing-entitlement result(-34018). This fixed query construction; it did not prove a complete unhosted save/read/overwrite/delete roundtrip. The native roundtrip regression is explicitly ignored in ordinary unhosted iosTest, which has no legitimate app entitlement host; assertions were not weakened.

The native UI reviewer then verified a real protected Customer practice login and reopen in a normally development-signed app. Customer's plaintext `auth_session` was absent afterward. Vendor's actual protected login also succeeded. Hub final protected restore also succeeded; Rider protected Senam restore and Komlan post-process-restart identity also succeeded; the separate Rider locale reset was corrected and its French process-restart retest passed; shared code/compiler success is not a substitute. Existing supported session records migrate only after checked secure write and readback, preserving legacy values on failure.

## Remaining exact device/provider checks

1. Final Customer `dc53ca6` restore/Gourde/detail/Add/quantity and basket-after-process-restart were exercised. Inspect any genuinely reachable remaining controls; scrolling did not move, so do not label below-fold delivery/checkout controls tested. Compare all delivery amounts against the server quote after supplying an authorized fixture destination.
2. Final Vendor `54db35b` Mawuli protected restore/scoped dashboard and clean logout were exercised. Earlier `0e0c846` covered Yawa preparation/store/catalog scope and account switch. Inbox/negotiation and below-fold activity still need explicit inspection. Check the active merchant is actual server membership. Do not treat preparation actions on an existing order as safe to replay blindly.
3. Final Hub `1e0030a` Dede restore/history, supported auth, Esso scope and rejected invalid pickup were exercised. Parcel-history fix/retest is complete. Remaining unverified work is actual return intake/inbox and any settings writes, a final API-shutdown recovery drill and an authorized valid manual pickup/QR drill on a dedicated unused fixture. For any authorized pickup drill, use a fresh actual fixture code/QR, verify missing identity/wrong code are rejected, then read the canonical server result. Test return ID+server PIN intake only on a dedicated unused fixture; do not execute account deletion.
4. Rider staged journey `59b3478` and final preference build `a078aa6` passed the observed account/locale/earnings checks. Still unverified: notification/preference writes beyond language, incident submissions, physical scanning, GPS/background behavior and external providers. For a new authorized fixture journey, use an unused paid/ready order, offer it through normal operations, test blank custody note and wrong PIN, then valid proof; refresh History/Earnings and compare the customer/admin order. Do not replay physical actions blindly on the completed order.
5. On each actual app, exercise refresh and a bounded offline/auth-denied state, restore the network/session and ensure old-account rows or success notices do not survive a changed scope. Record read-only screens separately from actions that changed server history.
6. Android visual review remains blocked by the available CUA surface: the standalone emulator Qt window is absent from app inventory and exact executable lookup was rejected. Installation/cold-launch/process logs are the available smoke evidence; no all-screen Android parity claim follows.
7. Real Google/Apple provider configuration, physical camera capture/scanning, GPS/background work, push delivery, real hardware/lockers and provider money movement remain unverified. Local fixture accounts do not exercise external identity/payment infrastructure. The Hub manual QR/PIN path is implemented; a camera scanner, physical SDK and batch collection are explicitly unavailable.
8. Existing Hub legal export and older-Android/native-provider behavior remain unverified. No successful PDF export is inferred from the common legal screen. New Hub operational copy is EN/FR; Ewe/Mina preference is retained with English fallback for new strings.

Simulator scroll/drag tooling initially did not move the viewport. Only actually observed content is in the UI ledger; below-fold settings/actions need explicit subsequent evidence. No source-only reachability is labelled visually checked.

## Home handoff operational dependency

The existing AdminOnly `POST /api/delivery/missions/{missionId}/delivery-pin` accepts a six-digit `rawPin` and explicit future `expiresAt`, stores only its hash and returns metadata. There is no declared PIN-issuance action in the admin generic controls (`delivery-mission-detail` has no actions), and the Customer app has no automatic delivery-PIN read/display path. Normal home handoff therefore remains operator-assisted; do not invent a client PIN or imply automatic customer delivery of that credential.

A future admin control can reuse the existing mission route/DTO, but the generic form currently saves pending request bodies to sessionStorage, unsuitable for a raw PIN. The existing create operation inserts a new row on every request and has no idempotency key/actor/reissue policy; its controller guards staff/home destination but not a complete live-issuance lifecycle. A production control needs ephemeral/masked handling, explicit issuance/reissue/replay rules and an approved customer distribution channel. This review did not invent a delivery channel or send credentials externally. Unassisted home-delivery acceptance remains conditional on a safe customer PIN-distribution workflow. The one consumed private test-PIN file was removed after the completed local journey.

## Preserved work and frozen evidence

Customer and Vendor project-file edits predate this review and remain unstaged/uncommitted:

| Protected path | SHA256 |
| --- | --- |
| `Sequo/iosApp/iosApp.xcodeproj/project.pbxproj` |`d4173c3aa8dfd8070b387ce6678f2da631a882637726d2bb633cf164ee5c7203`|
| `SequoVendor/iosApp/iosApp.xcodeproj/project.pbxproj` |`a7f7b50149460a7df096ea86593ca2644a0d463d0add8e59f42cd0c1c16e3af9`|

Hub's six pre-existing edits were rehashed unchanged; none was staged by live-workspace commits:

| SequoHub protected path | SHA256 |
| --- | --- |
| `README.md` |`9d8cd42e32bbecaaa120e1d88021589953f5ee78cc3bc63ddedf4aad2df9e272`|
| `STORAGE_PENALTIES.md` |`e220b6ca8da8f6edaf9da0f9fc88c142e328a809b2c4a169eadec6e62be797b8`|
| `shared/src/commonMain/kotlin/dev/orestegabo/sequohub/core/hub/HubRepository.kt` |`f703ea78a096626b759f8aa23404fb1764c1cdc8d97532b7c2b2a17f5e681625`|
| `shared/src/commonMain/kotlin/dev/orestegabo/sequohub/core/localization/SequoStrings.kt` |`35acc135c83c000b3bf67cb96a8599924b480e3f3ef5579bd00de259150575d6`|
| `shared/src/commonMain/kotlin/dev/orestegabo/sequohub/feature/hub/HubDashboard.kt` |`1294c349c586342ce2a45b6b6f60b3faf85c979a6835cce9e61ad4598483cf4d`|
| `shared/src/commonMain/kotlin/dev/orestegabo/sequohub/feature/legal/LegalScreen.kt` |`bac97c070c6f5cef96f97e1f880c8c0d19d33ff04bda143e95ac9a94ee158af3`|

Current Rider source is clean. The Customer historical malformed pricing-test typo was already restored to its exact intended HEAD in Task 28; this review does not reintroduce it or claim a filtered suite.

Existing uncommitted business decision notes, audit/output files and unrelated financial documents were neither staged nor rewritten. Subscription/referral/loyalty rates remain unapproved/inactive by default; staging delivery ETA/zone values and the refund deadline unit remain open business decisions. Native UI changes do not resolve them.

The frozen candidate `sequo-mvp-rc-20261108-01` was not rebuilt or repackaged. Read-only manifest SHA256 at this review: `61398bffd7cfc1cbd0a380b7c835920ba659ac273d33fd998af9825defa7efda`. This matches the recorded [Task 28 acceptance evidence](TASK28_RELEASE_ACCEPTANCE.md), whose state remains NOT_DELIVERED; this review creates no receipt or contractual clock. Existing candidate provenance and conditional local edits remain explicit.

## Exact changed-file inventory

Paths below are relative to the named repository under `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/`. They come from the focused commits listed above, not blanket staging or unrelated dirty files. This inventory includes committed Vendor status polish, Customer nullable storefront mapping and the API projection fix. Committed Hub and Vendor final UI/logout follow-ups are included.

### Sequo

```text
scripts/acceptance_support.py
scripts/build-acceptance.py
scripts/tests/test_acceptance_support.py
shared/src/androidHostTest/kotlin/dev/orestegabo/sequo/CatalogApiClientTest.kt
shared/src/androidHostTest/kotlin/dev/orestegabo/sequo/CatalogSearchTest.kt
shared/src/androidHostTest/kotlin/dev/orestegabo/sequo/CustomerInboxClientTest.kt
shared/src/androidHostTest/kotlin/dev/orestegabo/sequo/LocalFixtureAuthClientTest.kt
shared/src/commonMain/composeResources/values-fr/strings.xml
shared/src/commonMain/composeResources/values/strings.xml
shared/src/commonMain/kotlin/dev/orestegabo/sequo/App.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequo/core/auth/AuthApiClient.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequo/core/auth/AuthRepository.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequo/core/catalog/CatalogApiClient.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequo/core/catalog/CatalogSearch.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequo/core/notification/CustomerInboxClient.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequo/feature/auth/AuthScreen.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequo/feature/settings/AppText.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequo/model/SequoModels.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/account/AccountContent.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/app/SequoShell.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/basket/BasketCards.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/catalog/ProductDetailContent.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/catalog/ShopCards.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/chrome/AppBars.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/components/CoreComponents.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/home/CatalogSearchContent.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/home/HomeCards.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/home/HomeControls.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/markets/MarketsContent.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/notifications/CustomerInboxText.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/notifications/LiveCustomerNotificationsContent.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/orders/OrdersContent.kt
shared/src/iosMain/kotlin/dev/orestegabo/sequo/core/auth/AuthSessionStore.ios.kt
shared/src/iosMain/kotlin/dev/orestegabo/sequo/core/auth/IosAuthKeychainStore.kt
shared/src/iosMain/kotlin/dev/orestegabo/sequo/core/auth/SecureTokenStorage.ios.kt
```

### SequoVendor

```text
scripts/acceptance_support.py
scripts/build-acceptance.py
scripts/tests/test_acceptance_support.py
shared/src/androidHostTest/kotlin/dev/orestegabo/sequovendor/IosAuthPersistencePolicyTest.kt
shared/src/androidHostTest/kotlin/dev/orestegabo/sequovendor/StoreStatusPresentationTest.kt
shared/src/androidHostTest/kotlin/dev/orestegabo/sequovendor/VendorSessionConcurrencyTest.kt
shared/src/androidHostTest/kotlin/dev/orestegabo/sequovendor/VendorSignOutTest.kt
shared/src/commonMain/composeResources/values-fr/strings.xml
shared/src/commonMain/composeResources/values/strings.xml
shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/core/auth/AuthApiClient.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/core/auth/AuthRepository.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/core/auth/KeychainWritePolicy.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/core/auth/LegacyAuthMigration.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/core/auth/LocalNativeAuth.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/core/auth/VendorSignOut.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/core/auth/VendorWorkspaceClient.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/core/network/SequoVendorHttpClient.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/feature/auth/AuthScreen.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/feature/settings/AppText.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/ui/account/AccountContent.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/ui/app/SequoVendorShell.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/ui/catalog/CatalogContent.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/ui/components/VendorComponents.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/ui/dashboard/DashboardContent.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/ui/notifications/NotificationsContent.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/ui/orders/OrdersContent.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/ui/store/StoreAvailabilityLabel.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/ui/store/StoreContent.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequovendor/ui/store/StoreStatusSummary.kt
shared/src/iosMain/kotlin/dev/orestegabo/sequovendor/core/auth/AuthKeychainStore.ios.kt
shared/src/iosMain/kotlin/dev/orestegabo/sequovendor/core/auth/AuthSessionStore.ios.kt
shared/src/iosMain/kotlin/dev/orestegabo/sequovendor/core/auth/SecureTokenStorage.ios.kt
```

### SequoHub

```text
LIVE_OPERATIONS.md
androidApp/build.gradle.kts
androidApp/src/main/AndroidManifest.xml
androidApp/src/main/kotlin/dev/orestegabo/sequohub/MainActivity.kt
androidApp/src/main/res/xml/backup_rules.xml
androidApp/src/main/res/xml/data_extraction_rules.xml
iosApp/iosApp/ContentView.swift
scripts/acceptance_support.py
scripts/build-acceptance.py
scripts/tests/test_acceptance_support.py
shared/build.gradle.kts
shared/src/androidHostTest/kotlin/dev/orestegabo/sequohub/HubHistoryPresentationTest.kt
shared/src/androidMain/kotlin/dev/orestegabo/sequohub/feature/live/HubBackHandler.android.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequohub/App.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequohub/core/auth/AdminLoginApprovalApiClient.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequohub/core/auth/AuthApiClient.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequohub/core/auth/AuthRepository.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequohub/core/auth/LiveHubSession.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequohub/core/hub/HubApiClient.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequohub/core/hub/LiveScopedHubRepository.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequohub/core/network/ApiHealthClient.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequohub/core/network/NetworkConfig.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequohub/core/network/SequoHttpClient.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequohub/feature/auth/AuthScreen.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequohub/feature/auth/HubAuthText.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequohub/feature/live/HubBackHandler.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequohub/feature/live/HubHistoryPresentation.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequohub/feature/live/LiveHubText.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequohub/feature/live/LiveHubWorkspace.kt
shared/src/commonTest/kotlin/dev/orestegabo/sequohub/core/hub/LiveHubRepositoryTest.kt
shared/src/iosMain/kotlin/dev/orestegabo/sequohub/core/auth/AuthSessionStore.ios.kt
shared/src/iosMain/kotlin/dev/orestegabo/sequohub/feature/live/HubBackHandler.ios.kt
```

### SequoRider

```text
scripts/acceptance_support.py
scripts/build-acceptance.py
scripts/tests/test_acceptance_support.py
shared/src/androidHostTest/kotlin/dev/orestegabo/sequorider/DevelopmentRiderSessionTest.kt
shared/src/androidHostTest/kotlin/dev/orestegabo/sequorider/KeychainWritePolicyTest.kt
shared/src/androidHostTest/kotlin/dev/orestegabo/sequorider/RiderLanguagePreferencesTest.kt
shared/src/androidHostTest/kotlin/dev/orestegabo/sequorider/RiderMissionClientTest.kt
shared/src/androidHostTest/kotlin/dev/orestegabo/sequorider/RiderPresentationTest.kt
shared/src/androidMain/kotlin/dev/orestegabo/sequorider/feature/settings/RiderLanguagePreferences.android.kt
shared/src/commonMain/composeResources/drawable/rider_brand_icon.png
shared/src/commonMain/kotlin/dev/orestegabo/sequorider/core/auth/AuthApiClient.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequorider/core/auth/AuthRepository.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequorider/core/auth/KeychainWritePolicy.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequorider/core/missions/DemoMissionRepository.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequorider/core/missions/RiderMissionApiClient.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequorider/core/network/RiderWorkspaceRequests.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequorider/core/notifications/RiderNotificationsApiClient.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequorider/core/notifications/RiderNotificationsRepository.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequorider/feature/auth/RiderAuthScreen.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequorider/feature/settings/AccountSettingsScreen.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequorider/feature/settings/AppText.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequorider/feature/settings/RiderLanguagePreferences.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequorider/ui/app/SequoRiderShell.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequorider/ui/chrome/RiderBottomBar.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequorider/ui/chrome/RiderNavigationIcons.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequorider/ui/components/RiderComponents.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequorider/ui/history/HistoryScreen.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequorider/ui/missions/MissionsScreen.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequorider/ui/payout/PayoutScreen.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequorider/ui/route/ActiveRouteScreen.kt
shared/src/commonMain/kotlin/dev/orestegabo/sequorider/ui/route/RiderMissionStep.kt
shared/src/iosMain/kotlin/dev/orestegabo/sequorider/core/auth/AuthKeychainStore.ios.kt
shared/src/iosMain/kotlin/dev/orestegabo/sequorider/feature/settings/RiderLanguagePreferences.ios.kt
shared/src/iosTest/kotlin/dev/orestegabo/sequorider/AuthKeychainRoundTripTest.kt
```

### sequo-api

```text
docs/DEVELOPMENT_MOBILE_ACCESS.md
src/main/kotlin/dev/orestegabo/sequo_api/domain/admin/AdminWebSurface.kt
src/main/kotlin/dev/orestegabo/sequo_api/domain/auth/DevelopmentMobileAccess.kt
src/main/kotlin/dev/orestegabo/sequo_api/domain/commerce/CommerceApi.kt
src/main/kotlin/dev/orestegabo/sequo_api/domain/delivery/DeliveryMissionPersistence.kt
src/main/kotlin/dev/orestegabo/sequo_api/domain/delivery/DeliveryReadinessDispatch.kt
src/main/kotlin/dev/orestegabo/sequo_api/domain/hub/HubMobileController.kt
src/main/kotlin/dev/orestegabo/sequo_api/domain/hub/HubMobileService.kt
src/main/kotlin/dev/orestegabo/sequo_api/domain/order/OrderFulfillmentPersistence.kt
src/test/kotlin/dev/orestegabo/sequo_api/domain/auth/DevelopmentMobileAccessPolicyTest.kt
src/test/kotlin/dev/orestegabo/sequo_api/domain/auth/DevelopmentMobileSessionHttpTest.kt
src/test/kotlin/dev/orestegabo/sequo_api/domain/commerce/CommerceServiceTest.kt
src/test/kotlin/dev/orestegabo/sequo_api/domain/commerce/MerchantCatalogHttpTest.kt
src/test/kotlin/dev/orestegabo/sequo_api/domain/delivery/DeliveryReadinessDispatchServiceTest.kt
src/test/kotlin/dev/orestegabo/sequo_api/domain/hub/HubMobileControllerTest.kt
src/test/kotlin/dev/orestegabo/sequo_api/domain/order/OrderFulfillmentPersistenceServiceTest.kt
```

### sequo-admin

```text
scripts/local-workspace.py
scripts/local-workspace.test.py
```

## Supporting detailed reports

- Customer storefront/public-metadata follow-up: `/tmp/sequo-customer-storefront-review.md`; API counterpart `/tmp/sequo-catalog-storefront-read-review.md`.
- Customer inbox/search: `/tmp/sequo-native-customer-inbox-review.md`, `/tmp/sequo-native-customer-search-review.md`.
- Vendor source/session/build: `/tmp/sequo-native-vendor-review.md`.
- Final Hub UI/history/onboarding polish: `/tmp/sequo-native-hub-ui-review.md`.
- Hub screen/source/build inventory: `/tmp/sequo-hub-screen-review.md`.
- Rider screen/source/build inventory: `/tmp/sequo-rider-screen-review.md`, `/tmp/sequo-rider-review-files.json`.
- Shared validated signing/payload helper: `/tmp/sequo-native-build-signing-review.md`.

Temporary build logs are machine-local reproduction evidence. Durable sanitized API/readback records, final Android startup results and native screenshots are committed alongside this report. Existing decision notes remain uncommitted as requested.
