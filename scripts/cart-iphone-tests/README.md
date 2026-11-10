# Physical iPhone cart checks

This separate XCTest host drives the installed normal Sequo or SequoVendor app through its real UI. It contains no HTTP clients, injected sessions or fixture login bypass. The existing simulator-only reference suites keep their simulator guards.

Run only on an explicitly authorized connected, unlocked development iPhone, signed into a fictional test customer. Live data can change. Use only seeded `[TEST]` products and explicitly labelled no-money checkout; never type a real wallet payment confirmation. Ordinary checkout/deletion taps are blocked by label, but coordinates and other actions still require operator review.

Build for testing with `xcodebuild -project scripts/cart-iphone-tests/CartPhoneTests.xcodeproj -scheme CartPhoneTests -configuration Debug -destination "platform=iOS,id=$PHONE_UDID" -derivedDataPath "$PRIVATE_OUTPUT/DerivedData" -allowProvisioningUpdates build-for-testing`. The project's development team must match your authorized signing membership.

Run `python3 scripts/cart-iphone-tests/run.py --authorize-physical-ui --device "$PHONE_UDID" --products "$PRIVATE_OUTPUT/DerivedData/Build/Products" --output "$PRIVATE_OUTPUT/inspect" --actions "$PRIVATE_ACTIONS"`.

Use `--app dev.orestegabo.sequovendor.SequoVendor` for the normal Vendor app. Both bundle IDs are explicitly allowlisted.

Actions are a JSON array, for example `[{"kind":"wait","label":"Cart"},{"kind":"tap","label":"Cart"},{"kind":"capture","label":"cart"}]`. Supported actions/assertions are documented by the Swift switch. `scrollUntil` defaults to scrolling toward the bottom; `value:"down"` scrolls toward the top. `allowLocationOnce` handles only Sequo's temporary location permission. Do not save a real GPS location during fictional-address checks.

Compose text editors appear as native TextViews. Gestures use the latest visible frame center because cached accessibility activation points can lag scrolling. Focus and animation waits are intentional. A missing below-fold label is a harness navigation issue until a screenshot demonstrates a product failure.

The runner requires a new private output directory and keeps generated runfiles, raw logs and results private. Typed passwords can appear in XCTest logs and encoded runfiles. Do not commit these artifacts or private action files. Inspect summaries/screenshots before repeating a failed mutation; the action may already have succeeded.

See [the completed verification](../../docs/verification/cart-phone-2026-11-10.md).
