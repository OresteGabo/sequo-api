# Installed iPhone fixture reference UI tests

This independent UIKit host and XCTest UI bundle inspect the four already installed fixture apps. No original app project is changed. No networking library, token helper, app install, fixture seed or business-action call is included. XCTest uses the normal protected fixture-account chooser and real native taps/swipes.

The guarded launcher accepts only simulator `59D90F12-C368-449B-8752-6BD86241E8DB`, requires it already booted, validates all four `build-target.json` files as LOCAL_FIXTURE on `http://127.0.0.1:55840`, and compares each installed debug payload hash with its identified artifact. The launcher builds the isolated host with `build-for-testing`, injects the nonsecret fixture configuration into the generated `.xctestrun` runner environment, then uses `test-without-building`. The configured plist stays beside the original so Xcode's `__TESTROOT__` paths remain correct. Standard Xcode simulator test-host signing is disabled; product-app signatures are untouched. No fabricated identity or entitlement is supplied.

Run as the designated device/build operator after unlocking the Mac:

```sh
# Saved expectations for the retained local database, if /tmp was cleaned:
cp docs/evidence/human-reference-devices-2026-11-09/api-admin-reference-checks.json \
  /tmp/sequo-device-reference-sheet.json

python3 scripts/device-reference-tests/ios/run.py \
  --simulator 59D90F12-C368-449B-8752-6BD86241E8DB \
  --release-id sequo-reference-device-20261109-01 \
  --output-dir /tmp/sequo-reference-xcuitest-next \
  --include-hub-confirmation \
  --hub-return-reference R2D-W22-814 --hub-typo-reference R2D-W22-815
```

Add `--only customer|vendor|rider|hub` for a focused rerun into a new output directory. `--prepare-only` validates local files and emits sanitized configuration without executing a device/build command. Expected real references come from `/tmp/sequo-device-reference-sheet.json`, never from generated client aliases. Each run preserves its `.xcresult`, screenshot attachments and source/payload provenance. The launcher also reads the official xcresult summary and requires the exact requested test count with zero failures and zero skips; missing/unknown summaries fail closed.

Checks:

- Customer Ama: visible refund-order C2J and delivered-order C96 references; no pickup/return credentials are opened.
- Vendor Yawa: visible MNS merchant part, then its C96 parent in the existing read-only detail.
- Rider Senam: visible DA3 in completed History and recorded Payout history. Completed cards have no detail action; current Payout displays D mission references rather than W ledger identities, so C96/W linkage remains server evidence rather than an invented UI assertion.
- Hub Esso: all three actual P parcel references and persisted history. Switch through normal sign-out and choose Dede for Returns.

The Hub checksum and cancellation check is mandatory whenever Hub is selected (the local awaiting-return reference must still be valid):

```sh
python3 scripts/device-reference-tests/ios/run.py \
  --simulator 59D90F12-C368-449B-8752-6BD86241E8DB \
  --release-id sequo-reference-device-20261109-01 \
  --output-dir /tmp/sequo-reference-xcuitest-hub-01 --only hub \
  --include-hub-confirmation \
  --hub-return-reference R2D-W22-814 --hub-typo-reference R2D-W22-815
```

The typo must show a local checksum error. The valid reference plus dummy `000000` may open the review dialog, but the test taps **Cancel**, never Confirm, then clears the dummy input. The six zeros are deliberately not a real PIN. No custody, payment, provider, order or catalog mutation is authorized by this harness.

Source assertions and project syntax can be checked without running a build/device. Actual accessibility exposure and UI results remain unverified until the operator executes XCTest. Missing controls/references fail rather than invoking a backend fallback. Existing role scopes, sessions and all app data stay under normal server controls; sign-in/out itself changes temporary session metadata.
