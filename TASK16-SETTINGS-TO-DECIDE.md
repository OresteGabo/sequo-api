# Task 16 — Settings SEQUO needs to decide

This note was originally left uncommitted for easy discovery. It was versioned on 9 November 2026 when the user requested committing all remaining work. Business decisions below remain open.

“Zone” means the delivery area where a service can be offered. “ETA” means the estimated arrival-time range shown to customers.

No commercial decision is required to test the code. Use an isolated local fixture area centred at coordinates 0,0 and clearly labelled test windows. Those coordinates/windows are synthetic and must not be presented as a real service promise.

Before enabling new production choices, SEQUO should decide:

- Which actual city/area is covered, with an approved dispatch origin and maximum supported distance.
- Express, classic and deferred arrival-time ranges, including whether they include merchant preparation.
- Whether any mode costs more or less than the existing distance formula. The code must not invent a premium, discount or rider tariff.
- Which active accounts are salaried SEQUO riders and which are independents, plus vehicle type and availability/capacity rules.
- Which active paid subscriptions qualify for SEQUO-rider priority. Discounts and SEQUO-funded shortfalls need approved amounts/policies.

Proposed safe rollout: preserve the existing checkout path, test explicit new modes with local fixtures, then enable each new choice only after its zone and ETA are configured. If a configured option becomes unavailable after a customer reviews it, require a fresh quote rather than silently switching services.

Task 16 implementation and verification results are recorded separately in technical documentation. This business-decision note remains at the repository root for easy discovery.

## Plain next step

Start with one small area the operations team already serves. Run a few controlled deliveries, including preparation time, and use those observations to choose conservative arrival ranges. Enable one new choice first, check the results, then enable the others. There is no need to guess a whole-city promise now.

As implemented, an active subscription in its current period under an active plan qualifies for dispatch priority; this does not add a discount or collect subscription payment. Automatic policy selection conservatively allows one outstanding job per courier. Decide whether those rules fit operations before rollout. The current shipping price formula is retained for every configured mode; no express premium was added.

The code and technical checks are recorded in docs/TASK16_DELIVERY_MODES.md. This note is now versioned; recording it does not approve the outstanding business values.
