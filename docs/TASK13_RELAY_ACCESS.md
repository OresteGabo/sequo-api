# Task 13 — Relay access and parcel scoping

## Contract evidence and chosen surface

Read the signed `contrat_mvp_sequo_signed-2.pdf`, pages 2–3, 5 and 12–13, and audit rows 16, 19, 57, 58, 108 and 126–130. Clauses 16/108 explicitly allow a simple web module; the dedicated app requires written inclusion in MVP. Selected surface: the API-served French `/relay-operator.html`. No change to SequoHub or sequo-admin and no assumption of approval for a dedicated Hub app.

The module uses existing WhatsApp OTP, device-bound refresh and logout endpoints. It lists assigned sites and persisted parcels, shows server custody timestamps, issues existing numeric/QR credentials, requires an identity-document confirmation for withdrawal and reports problems. QR input accepts the decoded value from a scanner or paste; camera scanning and customer credential delivery are not implemented by this task. Browser code and access tokens stay in memory; the tab's refresh token uses sessionStorage. Session rejection clears site/parcel data. New credentials require identity checks; existing credentials and historical parcel rows retain their stored policy.

## Server boundaries

- `RelayOperatorAccess.kt`: authority comes from `relay_points.operator_user_id`, the authenticated subject and ACTIVE site status. No user-ID-equals-site-ID fallback. One operator may have multiple explicitly assigned sites. ADMIN/SUPER_ADMIN retain authorized global access. Support keeps existing Hub operational read access, without becoming a parcel operator.
- `RelayParcelController.kt`: site ownership applies to list, detail, deposit, code issuance, release and problem. Optional detail query IDs cannot establish ownership. Release body site must match the persisted parcel. `/api/relay/parcels/sites` returns only site ID/name, never wallet or operator details. Detail adds custody event types/timestamps, excluding credentials, identity documents and internal metadata.
- `HubMobileController.kt`: the same persisted membership applies to scans, summary, locker availability, opening hours and control reads/history. Control mutations remain staff-only.
- `DeliveryMissionController.kt`: an operator cannot bypass parcel verification through generic delivery release flags. The linked parcel must already be picked up and belong to the operator's site; guessed or cross-site problem actions are denied. Existing admin and assigned rider transitions remain available. Courier parcel creation requires an assigned relay mission, rather than a general courier role alone.
- `RelayParcelPolicy.kt`: GeneralGoods remains eligible; Food, Perishable and newly expressible Sensitive are excluded. Actual basket category derivation, chosen destination and authorized rerouting belong to Task 14; this task does not claim client-provided classifications are authoritative basket evidence.
- `RelayParcelPersistence.kt` / `RelayParcelService.kt`: serialize credential issuance, release and custody changes by parcel lock. Persist failed attempts, bind release replay to actor/site, and avoid duplicate release events/consumption. New parcel, credential and event records use insert semantics so guessed IDs cannot merge over another site's history. Conflicting existing IDs are rejected. Concurrent duplicate primary-key insertion fails transactionally rather than overwriting a record.

## Acceptance evidence

`RelayOperatorHttpTest` uses real local HTTP, signed fixture JWTs, persisted users and two distinct site assignments. It verifies both operators' allowed list/detail/deposit paths, both directions of guessed-ID/forged-site denial, unauthorized code issuance/release/problem, Hub scope checks, role rejection, missing/invalid sessions, unassigned legacy rows, inactive sites, allowed admin reads, assigned versus other courier creation and the generic mission-release bypass denial. It issues credentials for both operators and releases one parcel numerically and another via QR, with identity validation. Wrong identity/code attempts persist; two simultaneous identical releases return success with one consumption/event. Reusing the credential or another site's credential/event ID fails; subsequent reads show the persisted state/history without raw codes.

Existing Hub, relay policy/persistence/fees, delivery controller and paid checkout/home journey regressions are included. This is local H2 evidence, not deployed staging, a production PostgreSQL migration exercise, or live OTP delivery.

Commands from sequo-api, verified 2026-11-08:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --offline test --tests '*Relay*Test' --tests '*HubMobile*Test' --tests '*DeliveryMissionControllerTest' --tests '*CustomerCartCheckoutHttpTest' --tests '*SecurityConfigCorsTest'
node --check src/main/resources/static/relay-operator.js
NODE_PATH=/opt/homebrew/lib/node_modules/@google/gemini-cli/node_modules node scripts/relay-operator-browser-check.cjs
```

API: 67 tests passed. JavaScript syntax check passed. Browser check passed at 390px width with deterministic mock HTTP: OTP UI, assigned list/detail, issuance, identity-confirmed release, updated list/history, hidden resolved actions, refresh restore, offline errors and expired-session data clearing. The browser check requires Node, `puppeteer-core` available through NODE_PATH, and Chrome (`CHROME_PATH` can override the default macOS path); it contacts only a loopback fixture server. `/tmp/sequo-task13-web.png` is its mobile screenshot. Mock browser checks do not prove live OTP/provider behavior.

## Deployment decisions and exact unverified staging steps

No schema migration or automatic reassignment was added. Before enabling operators, SEQUO must approve which existing login owns each site, assign its persisted user ID to `relay_points.operator_user_id`, and grant RELAY_PARTNER through existing authorized account administration. Do not infer assignments from legacy matching identifiers. Unassigned historical records remain intact and accessible to administrators; operators fail closed. Additional staff per site require a later approved membership model. Confirm the site's ACTIVE status. No production account or secret was used here.

1. Deploy this API to authorized isolated staging with its normal database and WhatsApp configuration. Open `https://<staging-api>/relay-operator.html` over HTTPS. Sign in with each of two approved relay accounts in separate browser profiles. No access-token pasting or client-selected merchant/site privileges are required.
2. Have an authorized administrator or assigned rider deposit eligible fixture parcels at sites A/B using existing `/api/relay/parcels` and logical staging slots. Verify each operator sees only its own site's parcel. For food/perishable/sensitive categories verify creation rejection. Full basket eligibility and wrong-destination deposit are Task 14 checks.
3. Associate a six-digit fixture pickup code and optional decoded QR value. Try B's parcel ID from A via GET detail, pickup-code, problem and release; expect 403, including when the supplied site is forged to A or B. Try list and Hub summary for the other site; expect 403. Use the same tests in the reverse direction.
4. For A, try an incorrect code and then omit the identity confirmation; verify failure without withdrawal. Confirm matching identity and the correct numeric code. For B, use the decoded QR value and identity confirmation. Repeat an identical request/key after a simulated lost response: one release/history entry only. A new key after consumption must fail.
5. Refresh the browser and restart the staging API; confirm parcel state and custody timestamps persist. Disconnect/reconnect the browser; confirm errors and explicit refresh recovery. Expire/revoke the session, then verify site/parcel data clears. Remove a site assignment or mark it inactive and verify subsequent operator calls fail. Confirm admin and assigned rider workflows still work.

These deployed staging, live OTP, scanner-device and PostgreSQL/restart checks remain unverified because staging access was not provided. Existing Task 12 paid local fixtures cover the home journey. Relay customer notifications and synchronization from parcel pickup into order/delivery completion still need their dedicated journey integration; this web module changes parcel custody through the existing parcel endpoints.

## Next prompt can depend on

Persisted site membership and central scope enforcement; existing route/request shapes; additive `/sites` and parcel `history` response fields; numeric/QR validation with identity confirmation; replay-safe locked parcel release; explicit sensitive-category exclusion; the simple operator web surface. Task 14 must establish trusted basket categories/destination, enforce the actual selected relay for courier deposit/reroute and use real persisted locker availability instead of trusting a submitted locker list.

## Changed files

- `src/main/kotlin/dev/orestegabo/sequo_api/domain/relay/RelayOperatorAccess.kt`
- `src/main/kotlin/dev/orestegabo/sequo_api/domain/relay/RelayParcelController.kt`
- `src/main/kotlin/dev/orestegabo/sequo_api/domain/relay/RelayParcelPersistence.kt`
- `src/main/kotlin/dev/orestegabo/sequo_api/domain/relay/RelayParcelPolicy.kt`
- `src/main/kotlin/dev/orestegabo/sequo_api/domain/relay/RelayParcelService.kt`
- `src/main/kotlin/dev/orestegabo/sequo_api/domain/hub/HubMobileController.kt`
- `src/main/kotlin/dev/orestegabo/sequo_api/domain/delivery/DeliveryMissionController.kt`
- `src/test/kotlin/dev/orestegabo/sequo_api/domain/relay/RelayOperatorHttpTest.kt`
- `src/test/kotlin/dev/orestegabo/sequo_api/domain/relay/RelayParcelPolicyTest.kt`
- `src/test/kotlin/dev/orestegabo/sequo_api/domain/hub/HubMobileControllerTest.kt`
- `src/main/kotlin/dev/orestegabo/sequo_api/domain/auth/SecurityConfig.kt`
- `src/main/resources/static/relay-operator.html`
- `src/main/resources/static/relay-operator.js`
- `scripts/relay-operator-browser-check.cjs`
- `docs/TASK13_RELAY_ACCESS.md` and `docs/POSTMAN_ENDPOINTS.md`
