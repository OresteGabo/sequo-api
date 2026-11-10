# KKiaPay payments for Sequo

## Current scope

Sequo uses KKiaPay as the transport for **Yas Togo / Mixx by Yas** and **Moov Africa Togo / Flooz** only. This follows the user's explicit two-operator scope and the existing MVP contract's payment section. Cards, other countries and other wallets are excluded.

The native Android and iOS checkout opens a short-lived Sequo payment page in the system browser. That page launches KKiaPay's hosted widget. Customers enter wallet credentials only in KKiaPay. Orders provides **Continue payment** and **Check payment** to recover after closing the browser or losing connectivity.

Collection defaults to disabled. Missing keys never enable payments. Live collection also requires an explicit approval flag. Account activation is pending the company's information; the cause of the dashboard sign-outs has not been established.

## Configuration when keys become available

Keep credentials in private server environment files, never Git, mobile resources, screenshots, chat or command-line arguments. The ignored local `.env.kkiapay.local` file is a staging file only; Docker Compose does **not** load it automatically. `.env.example` contains blank placeholders.

Set these in the deployed backend's private `.env`, preserving all existing unrelated settings:

```dotenv
KKIAPAY_ENABLED=false
KKIAPAY_SANDBOX=true
KKIAPAY_LIVE_APPROVED=false
KKIAPAY_PUBLIC_KEY=
KKIAPAY_PRIVATE_KEY=
KKIAPAY_SECRET_KEY=
KKIAPAY_WEBHOOK_SECRET=
KKIAPAY_PUBLIC_BASE_URL=https://api.sequoservice.com
KKIAPAY_REFUNDS_ENABLED=false
```

1. Complete KKiaPay's company/identity verification with the company's authorized representative. Do not assume sandbox keys are live keys.
2. Store the three **sandbox** keys privately. Choose a separate, random webhook secret of at least 32 characters. Never reuse a wallet PIN or an account password.
3. In KKiaPay, register `https://api.sequoservice.com/api/payments/kkiapay/webhook` for payment success/failure events, with that same secret (`x-kkiapay-secret`). The handler verifies every transaction with KKiaPay's authenticated server API; webhook fields alone never authorize an order.
4. Set `KKIAPAY_ENABLED=true`, retain sandbox mode and live approval false, then recreate only the API service using the existing deployment workflow. No mobile keys are needed.
5. Use labelled `[TEST]` products and test relay points. Choose the KKiaPay option rather than manual simulation. Use only KKiaPay-provided synthetic **Togo** wallet values. The published sandbox guide has inconsistencies in its Togo examples; ask KKiaPay for working Togo Yas/Moov sandbox numbers if needed. Do not enable Benin or cards as a workaround.
6. Verify both permitted operators, failed/cancelled/pending payments, closing/reopening the browser, repeat callbacks, wrong amounts, wrong checkout ownership, webhook authentication and delayed confirmation. Confirm one order and one transaction claim, with no real financial accrual or dispatch.
7. Confirm the provider's actual authenticated status response matches the strict parser: transaction ID, integer amount, XOF (or documented XOF default), `DEBIT`, `MOBILE_MONEY`, `source_common_name` of `mixx-tg` / legacy `tmoney-tg` / `moov-tg`, and an unambiguous `partnerId` or `externalTransactionId` matching the session. These fields are covered by synthetic fixtures but cannot be confirmed against this merchant account until keys are available. Unknown schemas or operators fail closed; inspect privately and update tests before enabling collection.
8. After successful sandbox verification and merchant approval for the Sequo marketplace use case, install live keys and the matching live webhook, set `KKIAPAY_SANDBOX=false` and explicitly set `KKIAPAY_LIVE_APPROVED=true`. Resolve all outstanding sandbox payments before changing mode. Check actual fees and settlement terms with KKiaPay. Complete any real-money acceptance payment yourself with a known amount.

Adding keys alone intentionally does not enable live charging. Check `/api/payments/providers`: the KKiaPay row must show `checkoutConnected=true`, with the correct `sandbox` value. Explicit database readiness `DISABLED` blocks new collection even with valid credentials. Previously made payments can still be reconciled while new collection is disabled, provided the matching credentials/mode remain configured.

## Payment integrity

- The backend creates a payment session only for the authenticated checkout owner and the reviewed amount. Signed launch links expire after 20 minutes; owners can obtain a renewed link from Orders.
- The widget limits `countries` to `TG`, `paymentmethod` to `momo`, and `providers.accept` to `mixx-tg` / `moov-tg`. The operator allowlist was checked against KKiaPay's current public widget implementation on 10 November 2026; its SDK documentation currently describes country and method filters more fully than provider filters.
- Server-to-server verification uses fixed official sandbox/live hosts, three API headers, timeouts and no redirects. It checks the amount, transaction identity, currency, operator and checkout correlation.
- Verified receipts and transaction claims prevent repeated callbacks from creating multiple orders or paying another checkout. Once a transaction is verified, the payment page disables its Pay button. Check payment can retry local reconciliation without a new charge. If payment is pending, wait and check before authorizing another attempt.
- Sandbox payments are limited to seeded test products and test relay points. They cannot be manually marked paid, reserve benefits, accrue real merchant/courier payouts or use normal rider dispatch. Existing manual test simulation remains available separately.
- Automatic refunds remain disabled until explicitly activated and verified. The adapter supports a verified full refund only, bound to its original checkout and environment. Do not blindly retry an uncertain refund.
- Merchant/rider payouts are not automatically activated by this integration. Approved payout destinations and the provider's marketplace arrangements are separate requirements.
- Account exports include nonsensitive payment-session metadata, never keys or signed launch tokens.

## Verification

Payment/checkout/account regression tests, Android client route tests and both selectable languages are covered. Android compilation and iOS arm64 compilation are included in build verification. The KKiaPay integration fixture can also run against a disposable loopback PostgreSQL database:

```sh
SEQUO_KKIAPAY_TEST_URL=jdbc:postgresql://127.0.0.1:55487/sequo_kkiapay_fixture ./gradlew test --tests '*KkiapayIntegrationTest'
```

It requires a disposable database named exactly `sequo_kkiapay_fixture`, role `sequo_fixture`, with no password, bound only to localhost. Never point this fixture at production. No fixture contacts KKiaPay or moves money. A real provider sandbox end-to-end test remains pending keys/account access.

## Provider assessment and sources

KKiaPay is a plausible supported provider for Togo, not a guarantee of uninterrupted service. Its FAQ lists Togo and mobile-money acceptance. The BCEAO's published payment-establishment list includes OPEN SI in Benin; that does not alone establish approval of every Sequo marketplace collection/payout arrangement in Togo. The status page showed a partial outage during the initial review; check individual Togo connector health before testing. Confirm fees, settlement timing and your specific merchant agreement with the company/provider.

- [KKiaPay FAQ](https://kkiapay.me/faq/)
- [KKiaPay service status](https://status.kkiapay.me/)
- [Account activation](https://docs.kkiapay.me/v1/compte/activation-de-compte)
- [JavaScript widget](https://docs.kkiapay.me/v1/plugin-et-sdk/sdk-javascript)
- [Current public widget loader](https://cdn.kkiapay.me/k.js) and [widget application](https://widget-v3.kkiapay.me/)
- [Official server SDK](https://github.com/kkiapay/nodejs-sdk)
- [Webhooks](https://docs.kkiapay.me/v1/tableau-de-bord/webhook)
- [Sandbox testing](https://docs.kkiapay.me/v1/compte/kkiapay-sandbox-guide-de-test)
- [Supported currency](https://docs.kkiapay.me/v1/paiements/devises-supportees)
- [BCEAO payment-establishment list](https://www.bceao.int/sites/default/files/2026-03/LISTE%20DES%20ETABLISSEMENTS%20DE%20PAIEMENT%20AGR%C3%89%C3%89S%20DANS%20L%E2%80%99UMOA%20au%2031%20janvier%202026.pdf)

## Deployment verification — 10 November 2026

- Backend release `kkiapay-prepared-20261110`, runtime revision `f3cb2a2a4dbad836a8120fe54a6e49be8ad89d70`, deployed to the existing OVH API. Public health is UP and schema V73 is applied.
- The complete artifact hash was verified against the local package. A private database restore passed migration with the checked product, cart, order, user and settlement records preserved. Fresh private backups were taken before activation; the validation container and database were removed afterward.
- Existing JWT, Apple login, database and encryption configuration was preserved. KKiaPay collection, live approval and refunds are explicitly disabled. The public provider status reports checkout unavailable, and the webhook rejects requests without credentials.
- All 57 backend payment/checkout/account regression checks and five Android host client/translation checks passed. The payment integration also passed on disposable PostgreSQL through V73. Android and iOS arm64 compilation passed; the signed normal Sequo iPhone app built and installed successfully. Native app source revision: `168704b`.
- No real KKiaPay transaction was attempted or verified. The company's activation, keys, webhook setup and actual provider sandbox acceptance tests remain pending. No live payment approval or payout configuration was submitted.
