# SEQUO MVP implementation prompts

**29 standalone prompts.** Open a numbered file and copy its fenced block into a separate AI coding chat. Each file includes the project root, signed contract, audit, scope, prerequisites and acceptance checks. Make the listed repositories available to that chat for any cross-repo changes.

Use the [signed contract](</Users/muhirwagabooreste/Downloads/contrat_mvp_sequo_signed-2.pdf>) for scope and the [4 November 2026 audit](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/output/SEQUO-MVP-AUDIT.md) for evidence. These prompts start from the current implementation. Some of the older prompt files in the repository describe scaffolding that is already in place.

## Run order

1. **Start 01, 02, 04, 05, 08 and 24.** These are independent starting points. If several changes share a repository, run them serially or use isolated worktrees. Start 03, 06 and 07 as soon as their prerequisites are clear. Send the provider-access and business-rule questions immediately.
2. **Run 08 → 09 → 10 → 11 → 12 as the main delivery path.** Use a standard product and home delivery to get the first staged order through the system. Use 07 to add configured products once the base quote works.
3. **Branch into relay, logistics and order extensions.** Start 13 after role verification, even before the home-delivery flow is complete. Then use 14–23 and 27 according to the prerequisites below. Bargaining can start as soon as 03, 08 and 09 are ready.
4. **Run 25–26 when provider access and specifications arrive.** Continue staging acceptance while waiting. Keep its results separate from the live paid-launch gate.
5. **Finish with 28–29.** Rerun acceptance for any functionality added after the first staging pass. Use actual evidence to close each applicable contract requirement.

Ask SEQUO to confirm the refund deadline unit in #66, the effective business values, the Click and collect return policy, the level of Yas/Flooz integration and whether a dedicated Hub app is included in writing. The audit explains the distinction between the minimum relay module and the dedicated app. The public site and Sequo Developers are outside the listed software deliverables.

## Prompt index

| Prompt | Track | Start after | Contract |
|---|---|---|---|
| [01. Relay deadlines](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/output/sequo-mvp-prompts/01-relay-deadlines.md) | Independent | None | 60, 61, 139 |
| [02. Merchant catalog publishing](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/output/sequo-mvp-prompts/02-merchant-catalog-publishing.md) | Independent | None | 21, 22, 110, 116 |
| [03. Vendor bargaining eligibility](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/output/sequo-mvp-prompts/03-vendor-bargaining-eligibility.md) | Independent | None; coordinate with 02 if editing the same files | 54, 117 |
| [04. Real admin reads](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/output/sequo-mvp-prompts/04-real-admin-reads.md) | Independent | None | 15, 36, 107 |
| [05. Accounts, roles and profiles](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/output/sequo-mvp-prompts/05-accounts-roles-and-profiles.md) | Independent | None | 17–20, 109 |
| [06. Product photos and generic images](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/output/sequo-mvp-prompts/06-product-photos-and-generic-images.md) | Independent | 02 | 23, 24, 116 |
| [07. Food options and toppings](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/output/sequo-mvp-prompts/07-food-options-and-toppings.md) | Independent | 02 | 25 |
| [08. Customer basket and server quote](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/output/sequo-mvp-prompts/08-customer-basket-and-server-quote.md) | Critical path | None; use plain products first, then cover 07 | 22, 28, 35, 39, 40, 70, 110, 131, 132 |
| [09. Persist checkout and order history](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/output/sequo-mvp-prompts/09-persist-checkout-and-order-history.md) | Critical path | 08 | 26, 27, 43, 104, 113, 114 |
| [10. Isolated staging payment exercise](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/output/sequo-mvp-prompts/10-isolated-staging-payment-exercise.md) | Critical path | 09 | 27, 42–44, 113 |
| [11. Merchant prepares a staged order](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/output/sequo-mvp-prompts/11-merchant-prepares-a-staged-order.md) | Critical path | 09, 10 | 30, 118, 119 |
| [12. Rider home delivery and customer tracking](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/output/sequo-mvp-prompts/12-rider-home-delivery-and-customer-tracking.md) | Critical path | 10, 11 | 13, 36, 105, 114, 121–124 |
| [13. Relay access and parcel scoping](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/output/sequo-mvp-prompts/13-relay-access-and-parcel-scoping.md) | Relay | 05; can start before the home journey is complete | 16, 19, 57, 58, 108, 126–130 |
| [14. Customer relay choice and rider deposit](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/output/sequo-mvp-prompts/14-customer-relay-choice-and-rider-deposit.md) | Relay | 08, 09, 12, 13 | 56, 57, 112, 122, 123, 126 |
| [15. Relay pickup, history and overdue operations](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/output/sequo-mvp-prompts/15-relay-pickup-history-and-overdue-operations.md) | Relay | 01, 13, 14 | 58, 60, 61, 108, 127–130, 139 |
| [16. Delivery modes, workforce and dispatch](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/output/sequo-mvp-prompts/16-delivery-modes-workforce-and-dispatch.md) | Logistics | 12; independent domain checks may start sooner | 3, 4, 31–35, 41 |
| [17. Consolidation and cooperative approval](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/output/sequo-mvp-prompts/17-consolidation-and-cooperative-approval.md) | Logistics | 11, 12 | 29, 37, 38 |
| [18. Customer and seller negotiation](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/output/sequo-mvp-prompts/18-customer-and-seller-negotiation.md) | Order extensions | 03, 08, 09 | 50–55, 111, 136, 137 |
| [19. Customer return request and status](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/output/sequo-mvp-prompts/19-customer-return-request-and-status.md) | Order extensions | 12 | 62, 63, 115, 138, 140 |
| [20. Return custody, inspection and refund instruction](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/output/sequo-mvp-prompts/20-return-custody-inspection-and-refund-instruction.md) | Order extensions | 15, 19; business confirmation for the deadline unit | 63–66, 120 |
| [21. Subscriptions, referral and effective parameters](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/output/sequo-mvp-prompts/21-subscriptions-referral-and-effective-parameters.md) | Order extensions | 08, 09; approved business values for production | 41, 46–49, 131–140 |
| [22. Commissions, rider pay and SEQUO shortfall](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/output/sequo-mvp-prompts/22-commissions-rider-pay-and-sequo-shortfall.md) | Order extensions | 12, 16 | 22, 41, 67–70, 125, 133 |
| [23. Merchant sales, return and payment visibility](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/output/sequo-mvp-prompts/23-merchant-sales-return-and-payment-visibility.md) | Order extensions | 11, 20, 22 | 14, 106, 120 |
| [24. Yas and Flooz provider integration preparation](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/output/sequo-mvp-prompts/24-yas-and-flooz-provider-integration-preparation.md) | Independent | None; coordinate with 10 on shared payment files | 42–45, 150 |
| [25. Live collections and order reconciliation](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/output/sequo-mvp-prompts/25-live-collections-and-order-reconciliation.md) | External gate | 09, 10, 24; official provider specifications and authorized sandbox access | 27, 42–44, 113 |
| [26. Live refunds and electronic rider payouts](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/output/sequo-mvp-prompts/26-live-refunds-and-electronic-rider-payouts.md) | External gate | 20, 22, 24, 25; authorized provider refund/payout capability | 44, 45, 65, 66, 125 |
| [27. Minimum admin actions and access checks](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/output/sequo-mvp-prompts/27-minimum-admin-actions-and-access-checks.md) | Operations | 04, 05; add each domain action once its workflow is ready | 15, 18, 36, 38, 64–66, 69, 107 |
| [28. Deploy a testable release and run acceptance](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/output/sequo-mvp-prompts/28-deploy-a-testable-release-and-run-acceptance.md) | Acceptance | All applicable workflows above; keep a distinct paid-launch gate for live provider access | 11–16, §9, 104–140, 152 |
| [29. Handoff and close the contract checklist](/Users/muhirwagabooreste/AndroidStudioProjects/SequoService/sequo-api/output/sequo-mvp-prompts/29-handoff-and-close-the-contract-checklist.md) | Acceptance | 28 and written commercial/operational decisions as applicable | 7–10, 59, 71–103, 141–153, §§9, 12–15 |
