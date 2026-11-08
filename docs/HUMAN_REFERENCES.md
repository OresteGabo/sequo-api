# Human-readable references

SEQUO display references use **nine letters/digits, grouped `XXX-XXX-XXX`**. For example, `C12-345-6E6` is a structurally valid order reference; it is a protocol example, not evidence that an order exists.

The first symbol identifies the record kind, six symbols are randomly allocated on the server, and the last two check transcription. Existing database IDs, foreign keys, order/delivery retry codes, immutable histories and payment-provider references keep their current values. PINs, QR secrets, passwords and access tokens do not use this format.

## Format v1

The alphabet is `0123456789ABCDEFGHJKMNPQRSTVWXYZ`, taken from [Crockford's human-readable base32](https://www.crockford.com/base32.html). Upper/lower case, ASCII spaces and hyphens normalize to the canonical representation. `O` normalizes to `0`; `I` and `L` normalize to `1`. Unicode lookalikes, control characters and other punctuation are rejected.

The checksum uses the [CRC-10/ATM parameters](https://reveng.sourceforge.io/crc-catalogue/1-15.htm#crc.cat.crc-10-atm): polynomial `0x233`, initial value and final XOR zero, MSB first, no reflection. SEQUO feeds the **seven five-bit type/payload symbols** into that recurrence, then appends the ten checksum bits as two base32 symbols. This is SEQUO's versioned wire format, not Crockford's optional modulo-37 checksum. The type is covered by the checksum.

The code detects a changed canonical symbol and a swap of two distinct adjacent symbols, including checksum symbols. Approximately one in 1,024 arbitrary same-type payload/check combinations passes the checksum. Equivalent spelling aliases intentionally normalize rather than fail. A valid checksum does **not** establish existence, ownership, an approved action or a payment.

Six random symbols give each kind 2^30 possible payloads. Allocation uses `SecureRandom`, a database uniqueness constraint, and bounded collision retries. Random allocation alone is never treated as a uniqueness guarantee or a security boundary. The format version and prefixes are reserved; changing the format requires a new protocol version and compatibility tests.

| Kind | Prefix | Stable identity mapped by the registry |
| --- | --- | --- |
| Checkout | H | Existing checkout ID |
| Customer order | C | Existing order ID |
| Merchant order part | M | Existing sub-order ID |
| Delivery mission | D | Existing mission ID |
| Relay parcel | P | Existing parcel ID |
| Return | R | Existing return ID |
| Consolidation/grouping | G | Existing manifest ID |
| User account | A | Existing user ID |
| Merchant | V | Existing merchant ID |
| Courier | T | Existing courier profile ID, distinct from its user ID |
| Relay site | S | Existing relay point ID |
| Product | B | Existing product ID |
| Merchant payout/accrual | F | Existing payout identity; never proof of a transfer |
| Settlement ledger entry | W | Existing ledger row ID, distinct from payout/accrual IDs |
| Cooperative | Q | Existing cooperative ID, distinct from grouping manifest IDs |
| Refund instruction | N | Existing instruction ID; distinct from refunded funds |
| Logical storage slot | K | Existing locker/slot ID |
| Negotiation | E | Existing bargaining thread ID |
| Consolidation final package | J | Manifest ID, only when a final package is recorded |

`finalPackageId` is an existing operator label, not a globally unique entity key. Its public package reference therefore maps to the manifest's one final package. Financial source references are typed only when the existing ledger source type establishes that identity.

## Persistence and compatibility

Migration **V61** adds `public_entity_references`. It does not rewrite any business record, history, provider binding or retry key. The mapping is immutable and unique by `(entity_kind, internal_id)` and by public reference. Preserve this table in backups and restores: losing it would change the references people have already recorded.

An existing record receives its stable reference when first projected by an authorized server path. Writable domain transactions can allocate atomically with their record; a read-only legacy projection uses a separate metadata transaction. Concurrent allocation returns the same winning mapping, and a collision generates another candidate without overwriting an existing mapping or aborting the business transaction. Ordinary repeated reads do not write metadata again.

Wire fields are additive: `publicReference` for the record, and named relationship fields such as `orderReference`, `merchantReference` and `actorReference`. Clients keep canonical internal IDs for navigation, authorization, list keys and retry payloads. Nullable fields and legacy display fallbacks support older API/client versions. Redacted identities must also have their relationship references redacted.

## Typed lookup

`POST /api/references/resolve` accepts `{ kind, reference, relayPointId?, legacyId? }` and returns only `{ kind, id, publicReference }`. Responses are not cached.

- Admin-only lookup resolves exact registered references. Subsequent domain reads/actions retain their existing role, ownership, version and readback checks.
- Relay operators may resolve only returns, after their authenticated relay assignment is verified. An already deposited return belongs to its assigned site. An awaiting return may resolve only at an eligible location permitted by its current server-owned return plan. No customer details or PIN are returned.
- A malformed new reference is rejected before registry/entity lookup. A valid unallocated or inaccessible reference is unavailable; the checksum never confirms a record exists.
- The explicit older-ID mode retains historical input. Admin's existing domain routes still accept canonical older IDs under their normal access checks; the separate older-ID resolver requires an existing projected mapping. Relay older-ID lookup first checks the actual scoped return. Invalid new references never silently switch to older-ID mode. The public registry is never populated from arbitrary admin input.
- The Hub and admin validate new input locally before requesting resolution. A failed/changed lookup must not reuse an earlier resolved ID. Actions use the frozen canonical ID and their existing idempotency keys.

## Maintaining the implementations

Canonical Kotlin codec: `src/main/kotlin/dev/orestegabo/sequo/reference/HumanReference.kt`. The four independently built mobile repositories carry identical copies. Admin's TypeScript copy mirrors `contracts/HumanReference.ts`. Published kind/format/checksum vectors are in `contracts/human-reference-v1.json`.

Run `python3 scripts/human-references.py` from the API repository to detect drift across all five consumers. Keep cross-language vectors and negative input tests when editing the protocol. There is no client-side reference allocator.

Validation results and the exact commands used for this change will be recorded in the implementation evidence before handoff. No provider connectivity or real financial execution is part of this change.
