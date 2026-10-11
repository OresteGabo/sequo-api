# Sequo API MVP Status

Source contract: `/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/docs/contracts/contrat_mvp_sequo.tex`

This file tracks only contract-listed MVP scope for the backend API.

| Contract item | Status | Progress | Evidence | Remaining MVP work |
| --- | --- | ---: | --- | --- |
| Central backend API | Done | 90% | `src/main/kotlin/dev/orestegabo/sequo_api/domain` | Continue endpoint hardening and app-driven gaps. |
| Auth and role model | Done | 90% | `domain/auth`, role authority policy | Final cross-app role QA. |
| Customer order/payment validation | In progress | 75% | `domain/order`, `domain/payment` | Add production provider adapters and status mapping. |
| Merchant fulfillment | Done | 85% | `domain/delivery/MerchantFulfillmentController.kt` | Ensure merchant membership lookup in all production cases. |
| Merchant catalog writes/photo upload | In progress | 55% | `domain/catalog`, `domain/commerce`, platform catalog routes | Add merchant-scoped create/update/deactivate/photo endpoints if not complete. |
| Delivery/rider missions | In progress | 75% | `domain/delivery` | Complete rider-facing payout visibility and app validation. |
| Relay/hub operations | In progress | 80% | `domain/hub`, `domain/relay` | Verify all hub app flows against endpoints. |
| Returns/refunds | In progress | 75% | `domain/returns`, `domain/payment` | Wire refund execution to provider adapter. |
| Settlements/payouts | In progress | 70% | `domain/settlement` | Add rider-scoped payout visibility and provider payout instruction path. |
| Bargaining | In progress | 75% | `domain/bargaining`, commerce DTOs | Finish app UX and merchant product-level enable/disable writes. |
| Admin web read/mutation surface | In progress | 70% | `domain/admin` | Complete safe mutations/config endpoints. |
| Notifications | Done | 85% | `domain/notification`, mobile guide | Final provider/device QA across app families. |

Recommended verification:

```bash
./gradlew test
```
