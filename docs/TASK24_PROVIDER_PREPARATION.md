# Task 24 - Yas and Flooz provider integration preparation

## Status and contract evidence

**PREPARATION ONLY - NOT CONNECTED.** No Yas/Flooz request, sandbox login, payment, refund or payout was executed. Passing internal fixtures is not evidence of provider connectivity. Approved technical specifications, environment access and SEQUO's exact integration acceptance level were not supplied or verified in this session.

The signed source `/Users/muhirwagabooreste/Downloads/contrat_mvp_sequo_signed-2.pdf` defines scope:42-45 (page4) permit integration or preparation for mixx by Yas and Flooz subject to technical availability, require payment before order validation, prioritize electronic transfers, and make rider transfers conditional on capability.150 (page13) identifies the exact integration level as a point to confirm. The relevant complete source pages and audit rows42-45/150 were read; audit estimates and repository design notes are not provider specifications. Current diffs and normal adapter, webhook, pending-checkout and customer payment-read entry points were inspected before edits.

Existing adapter stubs already refuse successful payment/refund/payout results. Two remaining gaps were addressed: deployment templates omitted existing URL/account config slots, and the normalized webhook could bypass the disconnected provider adapter using a generic SEQUO HMAC secret. Stored LIVE_READY/enabled values also needed to be separated from effective runtime readiness.

## Available primary provider material

Public pages reviewed on2026-11-08:

- [Yas Togo business financial services](https://yas.tg/business/grandes-entreprises/solutions-financieres/) advertises online/merchant collection and salary services with a business contact path. It does not supply the versioned request, signature, refund or payout contracts needed by these adapters.
- [Moov Africa Togo merchant Flooz](https://moov-africa.tg/moov-money/marchand-moov-money-flooz/) describes a Corporate merchant account, asks for an economic-operator card or CFE with NIF, the responsible person's identity document and a Corporate subscription agreement. Those documents belong in the operator's approved onboarding channel, never in Git or test fixtures. This page describes customer merchant payment options, not an API integration contract.
- [Moov Africa Togo Flooz Entreprise](https://moov-africa.tg/moov-money/flooz-entreprise/) advertises business salary and collection services and directs businesses to an agency. That does not establish API access for SEQUO's independent riders, refunds or marketplace settlement.

No reviewed public page provides the approved wire formats, credentials or callbacks for SEQUO. This is a finding about the material available here, not a claim that private documentation does not exist. Third-party examples, old community SDKs and APIs from other countries were not adopted. Direct versus approved intermediary integration must be decided by SEQUO with the operators; no new payment provider was silently added.

## Exact missing inputs per operator

Every cell below is outstanding or unverified in this session. SEQUO/operator owners should provide approved material and secure secret references, not paste credentials into this report.

| Input / owner | Yas Togo - mixx by Yas (`yas_togo`) | Moov Africa Togo - Flooz (`moov_africa`) |
| --- | --- | --- |
| Integration agreement / SEQUO + operator | Written required level under150; direct/approved intermediary route; enabled collection/status/cancellation/refund/rider-transfer operations. | Same written scope for the Flooz Corporate integration; confirm marketplace and independent-rider use. |
| Merchant onboarding / SEQUO + operator | Approved SEQUO legal merchant account, required onboarding checklist, eligibility and technical contact. Public business page does not determine the final checklist. | Verified Corporate acceptance and merchant account; operator confirmation that the public card/CFE+NIF, responsible-ID and Corporate-agreement checklist is complete for the chosen API product. |
| Approved technical material / operator | Versioned API/SDK/OpenAPI or signed integration guide with sample messages, status/error catalogue and change policy. | Flooz-specific versioned guide and samples for the selected Togo product; no community/other-country substitutions. |
| Sandbox / operator + deployment owner | Actual test host/environment, granted test credentials/certificates, merchant identifier, test wallets and approved test cases. | Actual Flooz test host/environment, test credentials/certificates, merchant identifier and wallets. |
| Production / operator + deployment owner | Live host, approved account, separate secret/certificate references, enabled scopes and go-live approval. | Live Flooz host/account, separate secret/certificate references, enabled scopes and go-live approval. |
| Authentication / operator | Exact auth/token/certificate mechanism, credential names, token lifecycle, rotation/revocation, clock tolerances and any source-IP requirements. Existing api-key slot is only an internal placeholder. | Exact Flooz auth/token/certificate mechanism and equivalent lifecycle/network requirements. No REST/OAuth/HMAC assumption. |
| Payment initiation / operator | Path/method/schema, amount unit/currency, customer authorization journey, reference binding, validation errors and timeout handling. | Corresponding Flooz initiation schema and authorization journey; do not collect a customer's wallet PIN in SEQUO. |
| Callbacks / operator + deployment owner | Registered HTTPS callback URL, raw payload/schema, signature/certificate verification and canonicalization, key rotation, timestamp/replay policy, event identity, ACK/error semantics and retry order. | Same Flooz-specific material and callback registration. SEQUO's `X-Sequo-*` headers are not presumed provider headers. |
| Idempotency / operator | Key field/header, scope and retention; retryable versus terminal failures; duplicate initiation and callback behavior; ambiguous-timeout lookup. | Exact Flooz key semantics and duplicate/timeout recovery behavior. |
| Reconciliation / operator + SEQUO finance | Authoritative status/query and statement mechanisms, provider transaction/reference identity, currency/amount/merchant binding, finality/reversal rules, delivery ordering and reconciliation cadence. | Flooz status/query/statement and matching/finality rules, including late/reversed transactions. |
| Refund / operator + SEQUO finance | Confirm capability, original-transaction binding, full/partial limits, authorization, idempotency, callback/status evidence and fees. | Same Flooz capability/specification; a return instruction is not an executed refund. |
| Rider/merchant transfers / operator + SEQUO finance | Confirm allowed beneficiaries, onboarding/consent, prefunding/balance, transfer API and references, idempotency, limits, fees and authoritative final status. | Confirm Flooz business transfer eligibility for this use, beneficiary/account requirements, funding, limits, fees and final status. Salary advertising alone is insufficient. |
| Acceptance / SEQUO + operator | Approved sandbox cases, support/escalation, operator sign-off, production smoke-test amount and refund/reconciliation plan. | Corresponding Flooz certification/go-live evidence and responsible contacts. |

No tariff, settlement SLA, authentication algorithm or operator endpoint is invented by this task.

## Configuration matrix

The existing config object has four generic slots for each operator. Their final mapping may change after the approved auth/schema material arrives. Secret values, account identifiers and full configured URLs are never returned by readiness diagnostics.

| Slot | Yas environment variable | Flooz environment variable | Internal property suffix |
| --- | --- | --- | --- |
| Operator environment URL | `YAS_TOGO_API_BASE_URL` | `MOOV_AFRICA_API_BASE_URL` | `api-base-url` |
| Opaque credential placeholder | `YAS_TOGO_API_KEY` | `MOOV_AFRICA_API_KEY` | `api-key` |
| SEQUO merchant account | `YAS_TOGO_MERCHANT_ACCOUNT_ID` | `MOOV_AFRICA_MERCHANT_ACCOUNT_ID` | `merchant-account-id` |
| Callback credential placeholder | `YAS_TOGO_WEBHOOK_SECRET` | `MOOV_AFRICA_WEBHOOK_SECRET` | `webhook-secret` |

Property prefixes remain `sequo.wallets.yas-togo` and `sequo.wallets.moov-africa`. All eight environment slots are wired consistently in base, Docker and production configuration and Compose. `.env.example` leaves them blank. Retained local development placeholders are rejected. Keep sandbox and production secret stores/deployments separate; do not commit real values, use operator URLs as client-supplied input, or interpret populated fields as certification.

Absent or malformed config disables payment readiness while the rest of the application may start. It does not invent a fake successful transaction or prevent unrelated catalog reads. Syntactically valid values only indicate configuration presence; no DNS, TLS, provider login or network request is performed by that check. No runtime switch can connect the current stubs, and the existing simulator guard still rejects runtime simulation.

## Runtime and fixture boundaries

`PaymentProviderAdapter` already names internal initiation, callback verification, status, refund instruction and payout instruction operations. Both provider implementations remain unconnected. The same adapter instances serve the payment processor and runtime callback verifier. Production callback acceptance must pass that provider verifier, which rejects until real approved verification/mapping exists. Currency, timestamp/replay window, event identity/payload hash and checkout reconciliation remain server enforced after verification.

The normalized `POST /api/payments/webhooks/{provider}` envelope is SEQUO's internal contract, not either operator's documented callback format. Its `eventId`, `checkoutId`, `paymentReference`, `providerReference`, `amountCfa`, `status`, `occurredAt`, optional legacy-XOF `currency`, and `X-Sequo-Webhook-*` headers are used by local fixtures. A future provider mapper must translate and authenticate approved raw evidence before the existing state transitions; it must not trust amounts/references/status posted by a customer.

Deployment decision: if an environment currently sends generic normalized HMAC callbacks, its owner must identify and approve that evidence source and implement the corresponding trusted adapter before relying on it again. Populating a webhook-secret slot is no longer sufficient. Existing events, paid orders and pending checkouts are not deleted or backfilled; reconcile outstanding transactions against authoritative operator evidence. There is no schema migration or automatic financial-history rewrite in Task24.

Accepted deterministic HMAC evidence is supplied only by an explicitly imported verifier in the test source set, for both provider IDs. That helper is excluded from the production jar and has no configuration/profile enable switch. The normal service still performs replay/idempotency and the normal pending-checkout listener handles accepted fixture evidence. Pending/failed/wrong-amount/wrong-currency/wrong-reference/duplicate/late events do not bypass order rules. Existing payment history and supported legacy XOF events are preserved. No provider transport is simulated in runtime.

The public `GET /api/payments/providers` is the reused readiness read. It keeps recorded readiness separate from effective readiness and reports only safe issue codes. Stub execution remains disabled even if old DB rows say LIVE_READY or all operations enabled. Customer availability requires both `apiConfigured` and `checkoutConnected`; configured/not-connected, unavailable, loading and read errors are distinct. Pending checkout status still comes from authenticated order/checkout reads, not a client success flag.

## Acceptance checks and verification

| Acceptance / contract point | Result |
| --- | --- |
| Exact missing inputs per operator (42,45,150) | The operator matrix above identifies the unavailable/unchecked inputs separately for Yas and Flooz, including scope/onboarding, environments/auth, operations, callback proof, retries and reconciliation. Actual provider integration and go-live acceptance are BLOCKED on those inputs. Preparation is implemented. |
| Absent / invalid configuration | Both adapters are exercised with absent, malformed/placeholder and syntactically present values across initiation, status, callback, refund and payout contracts. None produces payment validation, accepted callbacks or submitted/paid transfers. Missing fields and invalid URL/placeholder issues are reported as safe codes. |
| Explicit readiness | HTTP read with recorded LIVE_READY/all operation flags true still returns effective PLANNED, NOT_CONNECTED, checkoutConnected=false and all enabled flags false. Recorded DISABLED stays disabled; unknown provider is UNSUPPORTED. Secret/account/URL sentinels are absent, and stored settings remain unchanged. |
| Paid-before-validation (43) | Runtime-only adapter beans reject normalized callbacks for both provider IDs, including correctly signed internal fixtures and credential-like configuration; no webhook event, paid order or merchant sub-order is created. Missing/invalid callback headers also fail closed. |
| Internal fixtures / retries | Only the explicit test verifier accepts normalized HMAC. Both provider IDs process pending, reject invalid signatures, decline cross-provider checkout reconciliation and complete accepted evidence through the normal state transitions. Same/new event-ID retries preserve one paid order and own merchant sub-order. Existing wrong amount/currency/reference, failure, late-event and concurrent reconciliation regressions pass. |
| Customer surface (44) | Configured slots/stored flags alone cannot imply payment available. Legacy missing booleans fail closed. Failed/empty reads do not reuse earlier availability; refresh clears old display. Authenticated persisted checkout/order reads still determine actual status. Android/iOS main compile passes; device visuals remain unverified. |

Baseline API command (before edits) passed27 tests:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --offline test \
  --tests '*PaymentProviderAdapterTest' --tests '*PaymentProcessorConfigurationTest' \
  --tests '*PaymentWebhookServiceTest' --tests '*PaymentSimulationGuardTest' \
  --tests '*PendingPaymentCheckoutServiceTest' --tests '*CustomerCartCheckoutHttpTest'
```

Final focused API command passed71 tests with0 failures/errors:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --offline test \
  --tests '*PaymentProviderAdapterTest' --tests '*PaymentProcessorConfigurationTest' \
  --tests '*PaymentProcessingTest' --tests '*PaymentWebhook*Test' \
  --tests '*PaymentReadinessConfigurationTest' --tests '*PaymentProviderReadinessHttpTest' \
  --tests '*PaymentSimulationGuardTest' --tests '*ProductionStartupGuardrailsIntegrationTest' \
  --tests '*StartupSecurityChecksTest' --tests '*PendingPaymentCheckoutServiceTest' \
  --tests '*CustomerCartCheckoutHttpTest' --tests '*BargainingCheckoutHttpTest' \
  --tests '*DeliveryBenefitsCheckoutHttpTest' --tests '*SettlementJourneyHttpTest'
```

Main Kotlin compilation also passed. The initial new HTTP fixture expected400 for absent required headers; existing Spring security/error handling returns403 on that path. Only that fixture expectation was corrected to allow400/403; normal signed/invalid-signature callbacks still assert400 and runtime security was not loosened. Independent read-only review checked the adapter gate, simulator guard, state matching and diagnostic disclosure. Main config wiring was checked for all eight slots; `ruby -e 'require "yaml"; ARGV.each { |p| YAML.load_file(p); puts "#{p}: YAML parsed" }' docker-compose.yml src/main/resources/application-prod.yml` parsed both YAML files.

Customer commands from `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo`:

```sh
./gradlew --offline -I /tmp/sequo-task21-client-tests.init.gradle \
  :shared:testAndroidHostTest --tests '*BenefitsClientTest'
# Baseline PASS:2 existing tests.
./gradlew --offline -I /tmp/sequo-task21-client-tests.init.gradle \
  :shared:testAndroidHostTest --tests '*PaymentProviderClientTest' --tests '*BenefitsClientTest' \
  :shared:compileAndroidMain :shared:compileKotlinIosSimulatorArm64
# PASS:5 focused host tests, Android/iOS main compilation.
```

Full customer host-suite compilation is blocked by the pre-existing uncommitted `DeliveryPricingTest.kt` edit, containing `calculateDeliver yPricing`. It was preserved, not staged or silently fixed (SHA-256 `6e8d46cb71af4de574a91941df80245871412b17e83c7ff8d367fb17a0830eb4`). The temporary init script excludes only that file for the focused host run; it does not alter source or main compilation:

```groovy
gradle.projectsEvaluated {
    rootProject.findProject(':shared').tasks.getByName('compileAndroidHostTest').exclude('**/DeliveryPricingTest.kt')
}
```

PostgreSQL callback checks also passed2/2 tests (both iterate Yas and Flooz) with real Flyway throughV50 and Hibernate schema validation:

```sh
SEQUO_PAYMENT_CALLBACK_TEST_URL=jdbc:postgresql://127.0.0.1:55434/sequo_task14_payment_callbacks \
  JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --offline test \
  --tests '*PaymentWebhookFailClosedHttpTest' --tests '*PaymentWebhookInternalFixtureHttpTest'
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew --offline bootJar
```

The guarded PostgreSQL URL is a disposable task-owned loopback cluster on55434 with local-trust `sequo_fixture`; no production database or credentials were used. SQL readback confirmed successful migration50, exactly one fixture order for each provider and zero events with a production-rejected fixture ID. The server was stopped after verification. Production jar build passed; ZIP inspection of `build/libs/sequo-api-0.0.1-SNAPSHOT.jar` confirms the production `PaymentWebhookVerifier` class is present and the test fixture configuration, HMAC verifier and gate-test classes are absent.

Local logs: `/tmp/sequo-task24-{baseline,compile,api-tests,postgres,artifact}.log`, `/tmp/sequo-task24-customer-{baseline,checks}.log`; XML is preserved under `/tmp/sequo-task24-h2-results/` and `/tmp/sequo-task24-postgres-results/`. `git diff --check` passes in both touched repositories. Device, remote staging, provider credentials/connectivity and actual transfers remain UNVERIFIED, with exact reproduction steps below.

## Staging/provider reproduction - UNVERIFIED

1. Obtain the operator-owned inputs above through approved channels and record the guide/version and sign-off without storing credentials in Git. Confirm the required preparation-versus-integration level under150 with SEQUO. Until then, keep both operator integrations NOT_CONNECTED.
2. On authorized staging, read `GET /api/payments/providers` with empty slots, known placeholders, malformed/non-HTTPS URL and syntactically present values. None must claim connected checkout/refund/payout. Through an authorized fixture DB only, give a recorded row LIVE_READY/all enabled and verify effective readiness still fails closed; restore its original settings afterward. No production policy row should be changed to run this check.
3. Sign in as the staging customer and refresh the basket provider list. During a failed/expired/offline read, prior availability must disappear and a readable error appear. A configured slot alone must never display payment availability. Submit only through the normal reviewed checkout; an unverified payment cannot create fulfillment.
4. The generic SEQUO internal fixture body/signature must currently be refused by the runtime provider adapter even if every credential slot is populated. Verify no accepted webhook event, paid order or merchant queue entry appears. Real operator callbacks are not yet implemented; do not present this refusal as an operator sandbox test.
5. After a subsequent approved adapter implementation, register the operator-provided staging callback and execute the exact signed-off accepted/refused/cancelled/pending/duplicate/late/invalid-signature/wrong-reference/wrong-amount/currency cases and timeout/status reconciliation. Compare authoritative statements with persisted references and one paid order. Repeat independently for Yas and Flooz; keep all evidence scoped to test accounts.
6. Only after capability approval, test a refund and rider transfer through their separate authorized workflows, checking one instruction and independently confirmed provider status. Follow operator acceptance/go-live approval before any production credential activation. Task24 does not perform those transfers.

## What subsequent work can safely depend on

Provider IDs, existing internal request/result contracts, pending-checkout reconciliation and state/ledger separation remain the integration seam. Configuration presence and recorded database labels do not prove provider readiness. Runtime callbacks fail closed at the real adapter; local accepted fixtures require test-source injection. Subsequent integration must implement approved raw verification/mapping, initiation and authoritative status checks before enabling operations, and preserve existing reference/amount/currency matching, idempotency and histories. Real refund/payout work must keep instruction and confirmed payment distinct.


## Changed files and focused commits

sequo-api (24 files):

- [.env.example](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/.env.example)
- [PAYMENTS_ARCHITECTURE.md](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/PAYMENTS_ARCHITECTURE.md)
- [docker-compose.yml](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/docker-compose.yml)
- [docs/POSTMAN_ENDPOINTS.md](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/docs/POSTMAN_ENDPOINTS.md)
- [docs/TASK24_PROVIDER_PREPARATION.md](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/docs/TASK24_PROVIDER_PREPARATION.md)
- [src/main/kotlin/dev/orestegabo/sequo_api/domain/DomainConfig.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/DomainConfig.kt)
- [src/main/kotlin/dev/orestegabo/sequo_api/domain/payment/PaymentConfigurationStatus.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/payment/PaymentConfigurationStatus.kt)
- [src/main/kotlin/dev/orestegabo/sequo_api/domain/payment/PaymentProviderAdapter.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/payment/PaymentProviderAdapter.kt)
- [src/main/kotlin/dev/orestegabo/sequo_api/domain/payment/PaymentWebhook.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/payment/PaymentWebhook.kt)
- [src/main/kotlin/dev/orestegabo/sequo_api/domain/payment/PaymentWebhookController.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/payment/PaymentWebhookController.kt)
- [src/main/kotlin/dev/orestegabo/sequo_api/domain/platform/PlatformCommerceApi.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/kotlin/dev/orestegabo/sequo_api/domain/platform/PlatformCommerceApi.kt)
- [src/main/resources/application-docker.properties](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/resources/application-docker.properties)
- [src/main/resources/application-prod.yml](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/resources/application-prod.yml)
- [src/main/resources/application.properties](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/main/resources/application.properties)
- [src/test/kotlin/dev/orestegabo/sequo_api/domain/commerce/BargainingCheckoutHttpTest.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/test/kotlin/dev/orestegabo/sequo_api/domain/commerce/BargainingCheckoutHttpTest.kt)
- [src/test/kotlin/dev/orestegabo/sequo_api/domain/order/CustomerCartCheckoutHttpTest.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/test/kotlin/dev/orestegabo/sequo_api/domain/order/CustomerCartCheckoutHttpTest.kt)
- [src/test/kotlin/dev/orestegabo/sequo_api/domain/order/PendingPaymentCheckoutServiceTest.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/test/kotlin/dev/orestegabo/sequo_api/domain/order/PendingPaymentCheckoutServiceTest.kt)
- [src/test/kotlin/dev/orestegabo/sequo_api/domain/payment/PaymentProviderReadinessHttpTest.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/test/kotlin/dev/orestegabo/sequo_api/domain/payment/PaymentProviderReadinessHttpTest.kt)
- [src/test/kotlin/dev/orestegabo/sequo_api/domain/payment/PaymentReadinessConfigurationTest.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/test/kotlin/dev/orestegabo/sequo_api/domain/payment/PaymentReadinessConfigurationTest.kt)
- [src/test/kotlin/dev/orestegabo/sequo_api/domain/payment/PaymentWebhookFixtureConfiguration.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/test/kotlin/dev/orestegabo/sequo_api/domain/payment/PaymentWebhookFixtureConfiguration.kt)
- [src/test/kotlin/dev/orestegabo/sequo_api/domain/payment/PaymentWebhookGateHttpTest.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/test/kotlin/dev/orestegabo/sequo_api/domain/payment/PaymentWebhookGateHttpTest.kt)
- [src/test/kotlin/dev/orestegabo/sequo_api/domain/payment/PaymentWebhookServiceTest.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/test/kotlin/dev/orestegabo/sequo_api/domain/payment/PaymentWebhookServiceTest.kt)
- [src/test/kotlin/dev/orestegabo/sequo_api/domain/settlement/SettlementJourneyHttpTest.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/test/kotlin/dev/orestegabo/sequo_api/domain/settlement/SettlementJourneyHttpTest.kt)
- [src/test/kotlin/dev/orestegabo/sequo_api/domain/subscription/DeliveryBenefitsCheckoutHttpTest.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/src/test/kotlin/dev/orestegabo/sequo_api/domain/subscription/DeliveryBenefitsCheckoutHttpTest.kt)

Sequo (3 files):

- [shared/src/androidHostTest/kotlin/dev/orestegabo/sequo/PaymentProviderClientTest.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo/shared/src/androidHostTest/kotlin/dev/orestegabo/sequo/PaymentProviderClientTest.kt)
- [shared/src/commonMain/kotlin/dev/orestegabo/sequo/core/catalog/CartApiClient.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo/shared/src/commonMain/kotlin/dev/orestegabo/sequo/core/catalog/CartApiClient.kt)
- [shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/basket/BasketContent.kt](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/Sequo/shared/src/commonMain/kotlin/dev/orestegabo/sequo/ui/basket/BasketContent.kt)

Focused commits: API `b9aa455` (runtime provider verification and explicit test fixtures), `cf2f2ac` (effective readiness, safe configuration diagnostics and deployment wiring); customer `fdcd704` (stale readiness clearing and HTTP regressions). This source/evidence report and corrected internal-contract notes are committed separately.

Pre-existing API output/audit/invoice artifacts and the intentionally uncommitted Task16 decision note remain untouched. The customer pricing-test edit remains uncommitted and unchanged. No Vendor, Rider or admin source was changed in Task24.
