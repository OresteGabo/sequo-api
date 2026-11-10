# Physical iPhone cart and checkout — 10 November 2026

The installed normal Sequo customer app was exercised through XCTest UI gestures on an iPhone 12 Pro running iOS 18.7.1 against the existing deployed API. Dedicated fictional customer `customer.flow` was used. Personal basket data was not mutated. Raw logs, screenshots, credentials and device identifiers remain in private local artifacts.

| Scenario | Physical result |
| --- | --- |
| Required shirt options, unavailable XXL | Add blocked until valid size/colour; unavailable option disabled |
| Separate S/Indigo and L/Sable lines | Preserved as distinct rows and order lines |
| Repeated +/− across two variants | Correct row changed; untouched row quantity and frame stayed stable |
| Shoe 42 and 43; unavailable 46 | Correct selection, quantity and removal; empty cart reached |
| Rice 0.5 kg at 950 CFA/kg | Portion priced at 475 CFA; two portions retained their own quantity |
| Create/switch/edit addresses | Fake address saved and selected; edits persisted |
| Missing/invalid delivery pin | Save disabled; place-name-only links refused |
| Temporary iPhone GPS permission | Pin obtained; incomplete form still blocked Save; cancelled without persisting actual location |
| Edit selected address then checkout | Fresh quote generated; first no-money checkout attempt succeeded after correction |
| Home → relay → home → relay | Old relay cleared; checkout blocked until a point was selected again |
| Ineligible grocery relay | No eligible points; checkout blocked in relay mode |
| Home and relay checkout | Both submitted and test-confirmed through UI; cart cleared |

## Bugs corrected

Cart lacked an address create/edit action despite requiring an address. Added a validated editor using the existing authenticated address API, with optional one-shot location access and full coordinate-link input. Address kind is explicitly serialized and preserved on edits.

Editing the currently selected address retained its ID, so quote effects did not rerun. This caused a reproduced first-attempt 409 quote-change rejection. Address saves now invalidate quote freshness and trigger requoting. The same physical scenario passed on the installed corrected build.

Screens shared a remembered scroll position. Destination-keyed screen composition now gives each navigation destination a fresh scroll container. Quantity accessibility descriptions now include selected options so variants are distinguishable.

## Persisted no-money receipt

Home: checkout `cart-dff5e71ae24b2728632f7795e2a639bad202aca24735d2c7de2ab66f03371c64`, FastDelivery, items 13,500 CFA + delivery 400 CFA = **13,900 CFA**. Lines: two L/Sable shirts at 4,500 CFA each, one S/Indigo shirt at 4,500 CFA. The home destination is the newly saved fictional address. This order later auto-cancelled under the ordinary merchant-response deadline; vendor acceptance/fulfillment was outside this cart test.

Relay: checkout `cart-95593911cfe8204d06510892e94a188c49985557a00429b24d2361dc5c633499`, PointDeRelai, one EU-43 shoe item at 6,500 CFA + delivery 400 CFA = **6,900 CFA**, destination `live-test-v1-hub-tokoin`. It was accepted for fulfillment at verification time.

Both have validated `TEST_NO_FUNDS:` references. Read-only database checks found **zero** merchant payout accruals, delivery settlements, settlement ledger entries and provider webhook events linked to these checkouts/orders. The fictional saved address still has its seeded Lomé coordinates after the GPS form was cancelled. No real payment or physical delivery is claimed. Public API health was UP.

## Build and regression evidence

Signed normal iPhone build and installation passed. Android and iOS compilation passed. Five focused Android host tests passed: address API/explicit-pin parsing (2), cart quantity contract (2), full English/French text coverage (1). The final device crash-log query returned no available Sequo reports.

Successful private physical cases include variants14, quantity15, address20, home22, shoes24's initial quantity/removal segment, relay27, grains29, measure30 and location31. Some early harness attempts failed due keyboard focus, below-fold controls or stale accessibility activation points; these were investigated separately and are not reported as product defects. home21 reproduced the real stale-quote defect and home22 verified its repair.

The phone was left signed into `customer.flow` with an empty cart; the normal app remains installed. [Reusable physical-only harness](../../scripts/cart-iphone-tests/README.md). No backend business-code deployment was needed; the existing live address and guarded no-money endpoints were used. This covers the exercised scenarios, not real wallet integration, every network failure or vendor/rider/hub fulfillment.
