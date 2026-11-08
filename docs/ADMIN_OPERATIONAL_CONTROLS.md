# Recorded admin operational controls

These routes use the normal authenticated `ADMIN`/`SUPER_ADMIN` boundary and `Cache-Control: no-store`. They do not impersonate a customer, courier, merchant or relay operator. Mutation actors must also remain active administrators in the current database.

| Method and path | Contract |
| --- | --- |
| `GET /api/admin/web/orders/{id}` | Recorded order header, priced lines/quantities, recorded food selection IDs/label, checkout delivery destination and actor/time event history |
| `GET /api/admin/web/couriers` | Real courier IDs, linked user/display name, status, workforce/vehicle, pause state, active mission count, exact `updatedAt` |
| `GET /api/admin/web/couriers/{id}` | `{courier, payOverview, history}`; payOverview reuses recorded-pay calculations and is null for an unlinked legacy courier |
| `PUT /api/admin/web/couriers/{id}/workforce` | `{workforceType, reason, expectedUpdatedAt, idempotencyKey}`; workforceType is existing `SequoSalaried` or `Freelancer` |
| `GET /api/admin/web/relay-sites` | Existing site ID/name/status, operator binding/display name, locality and exact `updatedAt` |
| `GET /api/admin/web/relay-sites/{id}` | `{site, history}` |
| `GET /api/admin/web/relay-sites/{id}/slots` | Actual persisted logical slots: ID/site/grid/code/status/availability reason and timestamps; no sample lockers or physical door commands |
| `PUT /api/admin/web/relay-sites/{id}/operator` | `{operatorUserId, reason, expectedUpdatedAt, idempotencyKey}` |
| `DELETE /api/admin/web/relay-sites/{id}/operator` | Explicit removal with `{reason, expectedUpdatedAt, idempotencyKey}` |
| `GET /api/catalog/products/{id}/customizations?includeInactive=true` | Complete recorded food menu, including inactive products/groups/options, for admin editing; default public reads remain unchanged |

Order detail never returns raw checkout payloads, payment/provider references, PINs, credential hashes or free-text proof metadata. Destination/contact is available only on the selected admin order detail. A missing, malformed or mismatched legacy checkout snapshot preserves recorded order lines/amounts and returns `snapshotState=UNAVAILABLE_LEGACY` with unknown selections/destination; it does not infer data from today's catalog or customer profile.

Courier/site directories omit wallet account references and customer contacts. Recorded rider pay is not a completed provider transfer. Unknown workforce remains explicitly unknown; no salaried/freelance status is invented. Logical slot status is not a claim that a physical locker is connected or that a basket/customer may use it; existing server eligibility and custody rules still apply. No new slot mutation is provided.

## Guarded configuration writes

Each write requires a nonblank reason (up to1000 characters), the exact ISO `updatedAt` string read from that target, and a fresh stable idempotency key for the confirmed intent. Do not parse/reformat timestamps in the browser: PostgreSQL microsecond precision must survive readback. Missing fields fail400; changed revision, retry intent or ineligible binding fails409 with a readable `code`/`message`.

Mutation responses are `{record, operation}`. `record` is the current detail DTO; `operation` contains `id`, `action`, `actorUserId`, `beforeValue`, `afterValue`, `reason`, `idempotencyKey`, and server `createdAt`. Detail `history` contains the most recent50 recorded changes. V53 adds append-only history for new operations without fabricating legacy events.

Keys are bound to actor, target, action, value, reason and original expected timestamp across the party controls. Exact replay is checked before revision freshness and returns the original operation plus current state. It never reverts a later change. Changed intent/actor/target cannot reuse that key. Confirm a recovered write by the matching operation/history, not by assuming the current value still equals an older operation. An uncertain or failed readback is not permission to create a different retry key automatically.

Workforce writes lock the same courier record used by dispatch policy. They affect future assignments only; existing mission workforce/fee snapshots and ledger entries are not rewritten. A genuine workforce change is blocked when unresolved legacy mission/ledger workforce snapshots could otherwise be reinterpreted. An operator must review that history; this API does not invent a backfill. Existing authoritative policy assignment retains its active linked courier, capacity, vehicle and paid-order requirements.

Relay assignment requires an existing active `RELAY_PARTNER` user and an existing active site. It does not grant roles. Site locking, explicit READ_COMMITTED isolation and locking the target user serialize concurrent assignments; an operator cannot be bound to two active sites through these routes. Explicit removal preserves history and leaves existing custody records intact; admins retain their existing site access. Physical handoff, pickup credentials and parcel release still use their existing scoped routes and proof requirements.

Normal `GET /api/catalog/products/{id}/customizations` remains public and filters unpublished data. The explicit includeInactive flag requires admin authorization even for guessed IDs; it must be used by an admin replacement editor to avoid silently dropping hidden groups/options. Existing nested PUT validation remains authoritative; no atomic menu revision protocol is claimed by this additive read.

## Redacted diagnostic queues

`GET /api/admin/web/notification-outbox` and `GET /api/admin/web/account-deletion-requests` return recent administrative metadata, default50 rows, bounded to1..200 by `limit`. Each also supports `/{id}` with the same redaction and admin/no-store guard. Missing IDs return404.

Outbox rows expose event/aggregate IDs, event type, recorded processing status, attempt count and timestamps. They omit message payload, worker lease identity, provider bodies, tokens and recipients. Outbox PROCESSED means recorded event processing, not proven external delivery. No retry, resend or provider execution action is introduced.

Deletion-request rows expose ID, user ID, recorded status and requested/reviewed timestamps. They omit private reason/confirmation text. No approval, legal erasure or deletion is performed by these reads. Retention and fulfilment policy still needs its own authorized workflow.

## Dispatch assignment and audit

Existing admin assign/reassign, offer, unpause and assign-policy routes accept optional `reason` and `idempotencyKey` fields. The current admin UI always supplies a confirmed reason (maximum1000 characters) and stable key. Existing authorized no-body/older callers remain compatible; missing legacy reasons are recorded as empty, never invented. Pause retains its existing reason/actor record and does not claim this retry protocol.

Manual assignment, policy assignment and problem replacement share current courier eligibility: an active linked courier account/role, recognized workforce/vehicle, no active pause, and one active mission of capacity. New dispatch requires a persisted paid active parent order. An unverified legacy record remains readable but cannot enter a new assignment. The same courier-row locks coordinate manual/policy assignment and workforce edits. Existing assignment retry preserves original workforce, monetary and acceptance timestamps; a replacement refreshes only the new assignment's workforce snapshot.

`GET /api/delivery/missions/{id}/admin-actions` and `GET /api/delivery/missions/couriers/{id}/admin-actions` return the latest50 `AdminPartyChangeDto` records under the normal admin/no-store boundary. Actions are `ASSIGN_COURIER`, `REASSIGN_COURIER`, `OFFER_MISSION`, `ASSIGN_POLICY` and `UNPAUSE_COURIER`; actor and timestamps come from the authenticated server context. V56 extends the append-only V53 history constraints without altering applied migration checksums. The transition and audit commit together. Keys bind actor/target/action/desired value/reason; exact replay precedes further side effects, while changed intent conflicts. A current snapshot alone is insufficient to confirm an uncertain action: use its matching history key/actor/action.

## Read-only negotiation oversight

`GET /api/admin/web/bargaining-threads?limit=50&merchantId=<optional>` and `GET /api/admin/web/bargaining-threads/{id}` expose server-effective status, product/party IDs, quoted/accepted amounts, expiry, attempt counts/limits and actor/type/amount/time offer history. Lists are bounded to1..200. The projection omits private messages, customer contacts, retry keys, private floor/history and request fingerprints. Existing participant authorization and offer mutations are unchanged; an administrator cannot accept, reject or counter an offer on behalf of a party through these reads.
