# Four-app design alignment — 8–9 November 2026

This review aligns Sequo, SequoVendor, SequoHub and SequoRider with one SEQUO
visual language. The request and release identifier started on 8 November;
final review continued after midnight in Europe/Paris. This is a local fixture
build, not a production deployment or contractual delivery.

## What is shared

All four apps compile the byte-identical `SequoDesignSystem.kt` and use its
navigation, page header, panel, action buttons, typography, shapes and normal
light/dark palettes. Each app retains its own translated destinations, icons,
role boundaries and workflow callbacks. See [design rules](MOBILE_DESIGN_SYSTEM.md).

- Deep green, mint selection, off-white background and white panels.
- Matching floating bottom navigation, 24 dp icons, selected-tab semantics and
  readable compact EN/FR labels. Destination counts remain role-specific.
- Common title/subtitle hierarchy, 20 dp page gutter, 16 dp panel/section spacing,
  20 dp panel corners and 18 dp primary/outlined actions with 48 dp minimum height.
- Standard fields inherit common theme shapes; existing native safe areas are
  applied once. Customer layout measures header/navigation height rather than
  hiding content behind fixed overlays; Vendor removes doubled page gutters.
- Rider keeps higher-contrast modes and its existing language preference;
  Hub's six pre-existing edited files are preserved byte for byte.

## Checks and device observations

**Completed:** all four final source versions passed their existing host checks,
Android package builds and normally development-signed iOS package builds.
The final iPhone builds were opened individually. All four final Android APKs
cold-started without AndroidRuntime/libc fatal output; Android visual interaction
remains unverified.

| Repository | Final source | Existing host checks | Android package | Signed iOS package |
| --- | --- | --- | --- | --- |
| Sequo | `fecf2b9` | 49 passed | PASS | PASS |
| SequoVendor | `ce28a4c` | 58 passed | PASS | PASS |
| SequoHub | `e800c64` | 17 passed | PASS | PASS |
| SequoRider | `1c91c00` | 31 passed | PASS | PASS |

**155 tests, 0 failures/errors/skips**, counted once from the final XML snapshots.
Existing localization
coverage caught the new compact Customer label missing from its in-app string
maps; both EN/FR maps were completed and the final 49-test suite passed.

[Build ledger, hashes, commits and complete changed-file lists](evidence/design-review-2026-11-08/build-ledger.json)
binds all eight actual artifacts to their final source versions. APK and full
iOS payload hashes were recomputed and matched. The build ID is
`sequo-design-review-20261108 / LOCAL_FIXTURE`; outputs are under
`/tmp/sequo-design-review/{customer,vendor,hub,rider}-{android,ios}/`.

[Android startup evidence](evidence/design-review-2026-11-08/android-startup-smoke.json)
records COLD launch results, source IDs, APK hashes and `uiVerified=false` on
`emulator-5580` (Medium Phone API 36.1).

| App / real local practice account | Actual iPhone visual checks |
| --- | --- |
| Sequo / Ama | Home, Basket and Account; selected tab and quantity badge; French Suivi fits on one line. Basket retains real quantity 2 / 9 600 CFA, has panel padding and one page title. Dark Account uses the shared dark surfaces and readable navigation. |
| Vendor / Yawa | Auth shows no bottom navigation; normal local sign-in, Dashboard, Orders, Catalog, editor and Account reached. Existing 3-product scope and dashboard counts remain. French tabs remain readable. The open editor starts below the header without duplicate Catalog/intro or disabled Add clutter; no product saved. |
| Hub / Esso | Relais, Retours, Historique and Réglages reached. Header/nav now align after removing duplicated SwiftUI/Compose safe areas. Actual site, 3 occupied logical slots and persisted history are retained. Blank return fields keep receipt disabled; no custody/settings action submitted. |
| Rider / Senam | Protected restore, Missions/Archives, localized selected tabs, actual 10-mission history and genuine empty offer queue. Sign-out stays secondary; the repeated Missions heading is gone. Signed-out page has no nav and paints the shared light/dark background; normal practice sign-in restored Senam afterward. |

A bounded preferred-text-size increase on Rider's common-navigation build left
the tab labels readable; one decrease restored the original setting. This is
not exhaustive text-size coverage. Simulator appearance was restored to light.
Native checks used task-owned iPhone 16 / iOS 18.6 simulator
`59D90F12-C368-449B-8752-6BD86241E8DB`; no personal device was used.

## Visual comparison

Test screenshots are local artifacts and are no longer stored in this repository.
The recorded visual checks covered Customer Home, Basket and dark Account;
Vendor Dashboard and editor; Hub relay and return form; and Rider Missions,
History and dark sign-in.

These observations used the final builds except Rider History, which used the
`80fd0a0` checkpoint before the signed-out-background follow-up. Verification
results and reproduction steps remain recorded below.

## Reproducing the checks

From each app repository, the final existing test command was:

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew --offline :shared:testAndroidHostTest -PsequoApiBaseUrl=http://127.0.0.1:55840 -PsequoReleaseId=sequo-design-review-20261108 -PsequoEnvironment=LOCAL_FIXTURE
```

Package with the existing `scripts/build-acceptance.py` in each app, choosing
its matching output prefix (`customer`, `vendor`, `hub`, `rider`):

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' python3 scripts/build-acceptance.py --platform android --api-base-url http://10.0.2.2:55840 --release-id sequo-design-review-20261108 --environment LOCAL_FIXTURE --output-dir /tmp/sequo-design-review/APP-android
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' python3 scripts/build-acceptance.py --platform ios --api-base-url http://127.0.0.1:55840 --release-id sequo-design-review-20261108 --environment LOCAL_FIXTURE --output-dir /tmp/sequo-design-review/APP-ios --development-signing-reference /tmp/sequo-native-review/development-signing.json
```

The iOS option requires the owner's installed legitimate Apple Development
identity and private local reference; no credential or signing identity is in
the evidence. Android helpers perform assembly, and iOS helpers compile/link
Kotlin and build the native wrapper. All builds ran serially using cached tools.
Final local logs: `/tmp/sequo-{customer,vendor,hub,rider}-design-final-{host,android,ios}.log`.

`python3 scripts/mobile-design.py` passed for all four copies. A temporary
workspace regression also proved that one changed copy returns exit 1 and
`--sync` restores exact bytes/exit 0, without modifying the actual checkouts.
The common-source SHA256 is `8eb7bbd8c2a8e5189f01c8e6ef434853d7013fa02d988916cd107513710056e9`.

## Focused commits

- Sequo: `e531d75` feat(design): unify customer chrome and shared SEQUO mobile components; `0bf3d77` style(customer): keep localized navigation labels compact and avoid duplicate title; `fecf2b9` fix(i18n): register compact order navigation labels in both languages.
- SequoVendor: `0ae56e5` ui(vendor): adopt the shared SEQUO mobile design; `ce28a4c` ui(vendor): keep the catalog editor focused.
- SequoHub: `9dc8772` Unify Hub chrome and controls with the shared SEQUO design; `e800c64` Let Compose own Hub safe-area insets once on iOS.
- SequoRider: `073ff42` Unify rider screens with the shared SEQUO mobile design; `80fd0a0` Keep rider header sign-out secondary and remove repeated title; `1c91c00` Paint rider sign-in backdrop with the shared theme background.
- sequo-api: `eff8ed3` records shared design rules and the drift check; this report and evidence are committed separately.



## Boundaries

Design-only source changes retain existing auth, ownership, operational states,
credentials and policies. No new payment, order, delivery, release or refund was
performed for this design review. Existing synthetic practice records were read.
No external identity/payment provider or physical device capability is claimed.

Android visual interaction remains unverified because its emulator surface is
not available to the native UI tool. Both Android compilation/package results
and process startup are recorded independently from the iPhone visual review.
The earlier functional review and remaining operational PIN distribution/device
checks remain in [the native review](NATIVE_APP_REVIEW_2026-11-08.md).

## Maintenance

From `sequo-api`, run `python3 scripts/mobile-design.py` to detect drift. Review
canonical changes in Sequo first, then synchronize deliberately with `--sync`
and verify affected app builds. Customer/Vendor project-file hashes and all six original Hub edit hashes were
reverified unchanged. Existing decision notes remain uncommitted. The frozen
Task 28 manifest remains `61398bffd7cfc1cbd0a380b7c835920ba659ac273d33fd998af9825defa7efda`; this review does not start acceptance delivery or its contractual clock.
