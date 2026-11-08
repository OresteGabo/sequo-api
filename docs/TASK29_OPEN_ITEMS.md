# Task 29 — Open closeout items

These are action owners by role, not recorded appointments or party approvals. Each named person, recipient, operating account and restricted evidence reference remains to be confirmed. The signed PDF controls; the checklist does not decide payment, ownership, legal compliance, delay liability or contractual acceptance. No due date or service-level promise is invented.

The current candidate is `sequo-mvp-rc-20261108-01`, LOCAL_FIXTURE. The user confirmed no testable delivery has been recorded. Its reporting clock is NOT_STARTED; validation and warranty dates remain unconfirmed. See the [clause checklist](TASK29_CONTRACT_CHECKLIST.md), [technical inventory](TASK29_TECHNICAL_INVENTORY.md), [operations handoff](TASK29_OPERATIONS_HANDOFF.md) and [Task28 evidence](TASK28_RELEASE_ACCEPTANCE.md).

## O-COMM

**Commercial confirmation — OPEN.** Owner: the Parties. Dependency: their private payment records, applicable written terms and any amendments. Next action: confirm actual payments/balance, applicable currency terms and whether the clause 100/§13 handoff/rights conditions are satisfied. Record a restricted confirmation reference and permitted handoff scope. Do not infer settlement, an unpaid balance or rights from commits, accessible files, invoice filenames or fixture payments. No financial documents were inspected for this task.

## O-IDENT

**Party/execution details — PARTIES CONFIRM.** Owner: the Parties and their appointed advisers as needed. Dependency: the executed source and current identity/address/authority records. Next action: privately confirm Annex B 141–142 and 146–147, including formal execution details and the applicable written terms. Keep particulars and evidence in the restricted party file; no passport, address, bank or personal contact values belong in this checklist. No legal-validity or jurisdiction determination is made here.

## O-ACCEPT

**Actual delivery, validation and their separate clocks — OPEN.** Owners: Prestataire prepares the identified delivery; the Parties record delivery/validation and SEQUO appoints its test/report recipient. Dependencies: usable agreed test/demo access, the release manifest, written milestone references and the essential-journey results. Next actions:

- Keep the current candidate NOT_DELIVERED until actual delivery occurs; use the Task28 receipt procedure with actual timestamp/offset, timezone and written reference. Preparation does not start the maximum 50-calendar-day reporting period.
- Retain written blocking-anomaly reports and their reproduction/evidence. Confirm the validation record separately; do not infer it from passing local tests, expiry of a tool deadline or source availability.
- Record warranty start/end only against confirmed validation. The source states three months from validation, not 90 days and not three months from the build or delivery date. The tool does not declare legal acceptance.

## O-HOST

**Online test/production hosting and recovery — BLOCKED.** Owner: SEQUO approves the environment/budget and appoints the deployment and recovery owners; the technical operator implements. Dependency: an approved separate target, secure access references, DNS/TLS, database/storage, backups and operating responsibility. Next action: use the Task28 runbook and operations handoff to provision the approved test copy, resolve O-SEC, prove migration/restore and role isolation, then run remote acceptance. The documented production host is not assumed to be an authorized test target. Local services were verified and stopped; remote access/ownership was not verified.

## O-AUTH

**Human test cohort and normal sign-in — BLOCKED.** Owner: SEQUO account owner with the appointed technical/security custodians. Dependency: controlled test contacts, approved OAuth/client registrations and auth-delivery configuration for test app IDs. Next action: prepare the two-customer/two-merchant/two-rider/admin/two-relay cohort through normal authentication and approved role/site membership, then verify allowed/denied calls and restore/expiry on all apps. Keep contact mappings and credentials in secure configuration. Do not mint production test identities, expose OTPs or treat local programmatic fixture tokens as human sign-in proof.

## O-DIST

**Physical devices and distribution — BLOCKED.** Owners: SEQUO's developer-account owner and receiving mobile developer. Dependency: approved signing/team/provisioning access, permitted physical test devices, OAuth package/bundle registration and store/distribution accounts as needed. Next action: create signed Android/iOS test distribution builds against the approved test origin and execute each required authenticated journey. Existing debug APKs and unsigned simulator wrappers prove builds/cold launches only. External account/store costs remain separately confirmed under O-COST.

## O-PROVIDERS

**Yas and Flooz integration/operations — BLOCKED separately.** Owners: SEQUO/provider integration owner obtains access; the Parties confirm Annex B 150's required level; the Prestataire implements the approved remaining scope. Dependencies: each operator's official specifications, onboarding/capabilities, authorized sandbox and secure configuration, callback/status/finality/idempotency rules and an agreed incident/reconciliation process. Next action: confirm the required level in writing, obtain the missing inputs in [Task24](TASK24_PROVIDER_PREPARATION.md), then execute [Task25](TASK25_COLLECTIONS_RECONCILIATION.md)/[Task26](TASK26_REFUNDS_RIDER_PAYOUTS.md) checks. Both remain NOT_CONNECTED. Do not enable paid launch or equate an internal instruction/liability with transferred funds.

## O-POLICY

**Business/legacy decisions — OPEN.** Owner: SEQUO business/operations lead; the Prestataire applies approved values. Dependencies: written decisions for delivery zone/ETA, relay tariff and active legacy parcels, Click-and-collect return treatment, the missing unit after clause 66's “3”, and any enabled subscription/referral terms. Next action: approve each applicable value/treatment, update the existing effective settings/policies and verify the affected boundaries/legacy records. Source 8-day/30-day relay and 72-hour return rules remain evidenced; no tariff, refund-deadline unit or extra product rule is invented. User-confirmed unapproved production subscription/referral benefits remain inactive. See [Task16](TASK16_DELIVERY_MODES.md), [Task19](TASK19_CUSTOMER_RETURNS.md), [Task20](TASK20_RETURN_CUSTODY.md) and [Task21](TASK21_EFFECTIVE_PARAMETERS.md).

## O-SCOPE

**Definitive scope, planning basis and changes — OPEN.** Owner: the Parties. Dependency: the executed scope, actual schedule-start evidence and any traceable amendments/decisions. Next action: confirm the interface list and technical choice, retain the simple relay web module as the recorded candidate surface, and record any dedicated Hub/extra control/Maps/service change in writing. Record actual dependency/change events before agreeing schedule/cost/reorganization effects; do not infer a delay, fault or added charge from code history. The PDF's estimated schedule is not converted into a new promise here.

## O-MAPS

**Maps inclusion and fees — PARTIES CONFIRM.** Owner: SEQUO/business owner with the Parties. Dependency: Annex B 151 decision, selected service, budget and approved account/key configuration. Next action: record the included mapping capability and external-cost owner; verify only that approved path. Current trusted server distance fixtures are not proof of Google Maps integration or approval to incur fees.

## O-LEGAL

**End-user texts and compliance — PARTIES CONFIRM.** Owner: SEQUO and its appointed advisers. Dependency: approved legal/customer-facing documents, commercial/data/workforce/merchant/relay arrangements and any required assessments. Next action: record the responsible owner and restricted approval references before commercial operation. Clauses 76–77/95 place these outside the fixed development scope; this technical checklist supplies no compliance certificate or legal conclusion.

## O-RIGHTS

**Source/asset/third-party rights inventory — OPEN.** Owner: the Parties, relevant rights holders and the appointed receiving technical/legal reviewers. Dependencies: O-COMM, the clause 100/§13 authorized recipient/scope confirmation, reusable/third-party component inventory and asset licence/provenance evidence. Next action: review the recorded dependency/asset references, compile required notices/restrictions and confirm the permitted source/account handoff. No project-wide licence is added by assumption. Preserve SEQUO/titular assets and the source's retained know-how/generic-component/third-party exceptions. A local source copy does not establish that rights have transferred.

## O-REPRO

**Recipient source availability and clean-machine reproduction — OPEN.** Owners: Prestataire prepares; authorized recipient developer verifies. Dependencies: recipient repository access or an approved secret-reviewed source snapshot, exact release refs, dependency caches/bootstrap, O-RIGHTS and a decision on Vendor's preserved local Xcode edit. Next action: confirm the candidate commits are reachable by the recipient; then reproduce from the recorded refs using the technical inventory. No push or recipient access was performed here. Vendor's acknowledged edit is recorded by hash, not included as an authorized patch; review/supply it through the permitted channel or make a new clean identified build. Do not silently commit it or export whole personal Git history/caches/credentials.

## O-SEC

**Subsequent source update, 8 November 2026:** commit `0fb7f61` adds explicit admin-code encryption configuration, strict unsafe/default/reused-key startup checks, deployment/CI plumbing and 23 passing guard/encryption tests. The source guard gap is fixed in the current checkout. The frozen Task28 candidate is unchanged. Remote current-key inventory, pending-code continuity and a permitted staff-login check remain open; no key, ciphertext or running deployment was rotated or changed. See [deployment key continuity](../DEPLOYMENT.md).


**Key continuity and online verification — OPEN activation dependency.** Owners: Prestataire technical maintainer plus the appointed security/account custodian. Dependencies: restricted current-key/consumer inventory, pending admin encrypted codes and persisted FCM/relay/return credential continuity. Next action: approve a continuity-preserving plan, provide explicit environment-specific admin-code encryption configuration, add/test strict validation/configuration documentation and verify normal staff login before online activation.

Frozen Task29 evidence (the source gap is corrected by the update above): [AdminLoginCodeProtector](../src/main/kotlin/dev/orestegabo/sequo_api/domain/auth/AdminLoginCodeProtector.kt) has a development fallback; frozen-candidate [startup guardrails](../src/main/kotlin/dev/orestegabo/sequo_api/config/ProductionStartupGuardrails.kt) and environment templates do not cover that slot. Minimum length alone does not reject the fallback. The actual remote value is unverified. The frozen candidate does not contain a fix. The notification key also protects FCM tokens and derives customer relay/return credentials; blind rotation can break retained access. See [operations key continuity](TASK29_OPERATIONS_HANDOFF.md#key-continuity-and-open-security-item). No runtime key, configuration value, guard or stored ciphertext was changed in Task29. Contractual defect/coverage classification remains for the Parties; this is a technical activation gate.

## O-SUPPORT

**Support, warranty triage and ongoing service — OPEN.** Owner: the Parties appoint the SEQUO support coordinator, technical escalation and operating owners. Dependencies: an approved private reporting channel, O-ACCEPT's milestone records and any separate continuing-service agreement. Next action: record the intake/escalation contacts and support scope; use the operations handoff to route acceptance blockers, possible design/development warranty defects and maintenance/new requests without automatic billing/refusal. Covered warranty corrections are without additional charge under §15, distinct from continuing maintenance. No SLA, free ongoing service, warranty dates or maintenance agreement is asserted.

## O-COST

**External resources, expenditure and operating budget — PARTIES CONFIRM.** Owner: SEQUO's authorized budget/procurement/operations roles; the Parties confirm any written scope exception. Dependency: approved needs, supplier/account terms, physical resources and private payment evidence. Next action: record responsible budgets for maps/messaging/hosting/stores/operator charges/taxes/hardware/workforce and any separately agreed scaling/advanced-audit/service work. Clauses 59/71–80/94 document exclusions; a test fixture or repository does not purchase equipment, pay third parties or establish actual expenditure.

## Blockers and defect record

- **Known technical readiness item:** O-SEC, supported by current source inspection. No online/production-ready claim is made; preserving existing key-dependent records is required before a fix/cutover.
- **Uncompleted verification/access gates:** O-HOST, O-AUTH, O-DIST, O-PROVIDERS and O-REPRO. Essential authenticated Android/iOS/web acceptance remains unverified, as listed in Task28. These are not automatically classified as separate software defects or a contractual breach.
- **Decisions/confirmations:** O-COMM, O-IDENT, O-ACCEPT, O-POLICY, O-SCOPE, O-MAPS, O-LEGAL, O-RIGHTS, O-SUPPORT and O-COST. A named owner, written evidence reference and completed next action are needed before closing each applicable item.
- **Nonblocking visual improvement recorded in Task28:** the external admin icon font can show text ligatures when external resources are blocked. It is separate from the access/payment/security gates. Resolved Task28 findings remain resolved evidence, not newly opened claims.

Closure evidence must state who confirmed what, when, for which release/environment, and point to the permitted written record. Keep personal/financial/legal/secret material in restricted party or operations records. No external message, actual source/account transfer or legal sign-off has been performed by this documentation task.
