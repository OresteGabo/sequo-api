# Relay administration and SEQUO physical receipt

Signed scope: clauses16 and108 permit the existing simple relay web module;56–58 cover approved rerouting, exclusions and code/QR plus identity validation;59 excludes physical storage hardware;60–61 retain conditional fees strictly after8 days and explicit review/disposition at30 days;126 permits SEQUO or rider deposit;127–130 require credentials, operator withdrawal and custody/deadline history.

The existing rider deposit/release/returns policies remain authoritative. This addition supplies setup and SEQUO confirmation through the current authenticated admin website; it introduces no provider transfer, hardware command, storage tariff, automatic disposition, identity-image collection or new role grant.

## Routes

All new routes require a current active `ADMIN` or `SUPER_ADMIN` and use `Cache-Control: no-store`. Prefix: `/api/admin/web/relay-sites`.

| Method/path | Request / response |
| --- | --- |
| `GET /{id}/setup` | `{site,slots,history}`. Existing site DTO adds nullable latitude/longitude; slots reuse persisted logical-slot DTOs. History contains latest50 new setup/receipt operations; legacy history is not fabricated. |
| `POST /` | `{name,latitude,longitude,city?,neighborhood?,landmark?,reason,idempotencyKey}`. Generates a server site ID; no operator is assigned. |
| `PATCH /{id}/details` | Same site fields plus exact site `expectedUpdatedAt`. Established sites with slots cannot be relocated; complete legacy unknown coordinates or create a new site for a physical move. |
| `POST /{id}/slots` | `{lockerCode,expectedUpdatedAt,reason,idempotencyKey}`. The expected timestamp belongs to the site. Creates a real logical grid/slot, with a unique normalized local code; no hardware is operated. |
| `PUT /{id}/slots/{slotId}/availability` | `{status:AVAILABLE|MAINTENANCE,expectedUpdatedAt,reason,idempotencyKey}`. Timestamp belongs to the slot. Occupied or unresolved custody is rejected. |
| `POST /{id}/deposits` | `{missionId,lockerId,expectedMissionUpdatedAt,expectedSlotUpdatedAt,physicalReceiptConfirmed:true,reason,idempotencyKey}`. Confirms actual SEQUO physical relay receipt after an authorized recorded rider pickup. |

Mutations return `{record,operation,parcelId?,mission?}`. `operation` contains ID, site ID, action, authenticated actor, reason, stable retry key, before/after state maps and server time. Reasons are required and bounded to1000 characters. Timestamps must be sent exactly as read; do not normalize away PostgreSQL precision. Keys bind actor/action/site/source/payload/reason/revisions. Exact replay is checked before freshness, returns canonical operation plus current state and never rewinds later edits. Changed intent/actor/target conflicts.

V58 adds only an append-only `relay_administration_operations` table. No existing records, applied migration checksums, fee history, pickup credentials or operator assignments are rewritten. A unique key protects competing create attempts; a racing conflict must refresh/retry the same key. Site/slot operations also recheck replay after acquiring their authoritative row locks.

## Eligibility and custody

A new site may have an ACTIVE master record but cannot appear in checkout eligibility until its existing operator assignment and free logical-slot requirements are satisfied. `RelayAvailabilityService` continues to require valid coordinates, an operator, available capacity, opening hours and applicable control-state policy. The new HTTP integration test verifies this distinction.

SEQUO receipt requires a paid active persisted order, the mission's existing relay destination, recorded pickup and PICKED_UP_FROM_SELLER state with no open problem. Actual slot ownership, free capacity, current revisions and the existing food/perishable/sensitive exclusions are checked under the normal custody transaction. This action cannot force rider acceptance/pickup, change destination/assignment or validate a customer handoff. Normal mission, parcel, slot, customer credential and settlement transitions commit together. The actual administrator is recorded in mission receipt proof, parcel deposit history and the new operation audit; the assigned rider is retained. Fees and any accrued rider liability retain the existing policy; no provider transfer is implied.

The legacy raw `POST /api/relay/parcels` is now unavailable for new HTTP custody. It previously trusted client locker availability, source references and deposit timestamps. After normal role/site checks it returns400 `relay_guided_deposit_required`, directing callers to the assigned-rider or SEQUO receipt workflow. Runtime client search found no normal client caller; legacy records and internal service fixtures remain supported for reads, withdrawal and overdue decisions. Their historical unknown actors are preserved; new internal deposits can supply a server-owned actor without adding any actor field to the HTTP DTO.

The existing Hub slot-availability HTTP route now locks an existing site-owned slot, rejects unknown-slot upsert and manual OCCUPIED status, and refuses changes while current or legacy inconsistent active custody exists. Only the HTTP controller selects this strict mode; no caller-supplied override exists. Intentional internal atomic deposit/release and existing service fixtures keep their separate behavior.

## Admin panel

`RelayAdministrationPanel.vue` accepts the existing authenticated `request` function and `actorId`; it emits `changed` after confirmed readback. It uses required reasons, explicit target/consequence confirmation, frozen actor-scoped pending payloads, exact server-operation/history readback and safe reload/retry. Pending drafts use `sequo_relay_admin_pending:<actorId>` and are cleared by the parent on logout. Malformed, mismatched-site or mismatched-mission responses never become current editable previews.

Operator assignment remains the existing guarded management route. Code/QR validation, identity confirmation, overdue review and seller return remain in the selected relay operator module. No dedicated Hub application or physical locker integration is claimed.

## Verification

- `AdminRelayAdministrationHttpTest`: real normal fixture reconciliation, setup, eligibility before/after operator+slot, role denial, exact replay and collision, stale state, concurrent site revision, physical confirmation, pickup/paid/category/site requirements, actual staff actor, occupied-slot denial, raw-create rejection and legacy inconsistent Hub availability denial.
- `RelayOperatorHttpTest`: existing two-site ownership, guessed IDs, credential issue/release, wrong proof, one-time/concurrent release, overdue history and operator-controlled disposition, using explicit internal legacy fixtures.
- `HubMobileControllerTest`, `HubMobileServiceTest`: existing scoped operator and supported internal behavior.
- `H2V51FlywayCompatibilityTest`: complete current migration/schema validation including V58 (and later task migrations).
- Admin contract tests, type/build and `scripts/relay-administration-browser-check.cjs`: confirmation cancellation, frozen retry/readback failure, site/slot forms, physical receipt, lost-response reload recovery, actor history and expired-session clearing.

Exact commands, counts and limitations are recorded in the task's local evidence report. No staging, production, live fixture data, provider or physical device was mutated by these checks.
