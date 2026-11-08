# SEQUO mobile design system

Sequo, SequoVendor, SequoHub and SequoRider use the same theme, navigation rail,
page header, panel and action-button components. Each app keeps its existing
destinations, permissions, labels and business actions.

## Common source

The canonical file is maintained in the customer repository:

`Sequo/shared/src/commonMain/kotlin/dev/orestegabo/sequo/design/SequoDesignSystem.kt`

The other three repositories contain byte-identical copies at the same relative
path and package. This keeps each checkout independently buildable without an
unpublished dependency or a sibling Gradle project. App-specific adapters pass
their existing icons, translated labels, selected destinations and callbacks to
the shared components. Do not recreate those components inside an app adapter.

From `sequo-api`, check all four sibling checkouts:

```sh
python3 scripts/mobile-design.py
```

After reviewing an intentional canonical change, synchronize the other copies:

```sh
python3 scripts/mobile-design.py --sync
```

The check compares the entire source, including both palettes, typography and
component behavior. It complements compilation and visual review; it does not
claim that every screen has been exercised.

## Design rules

| Element | Common rule |
| --- | --- |
| Brand | Deep SEQUO green `#0B6B46`; mint selection; warm off-white `#F7F8F4`; white panels |
| Dark appearance | One complete dark palette; retain Rider's explicit higher-contrast modes |
| Typography | Platform system font, common Material sizes/weights and zero letter spacing |
| Page layout | 20 dp horizontal gutter applied once; 16 dp section spacing |
| Panels | 20 dp corner radius; subtle outline; 16 dp content inset |
| Main actions | 18 dp corners; at least 48 dp tall; shared enabled/disabled presentation |
| Header | Same title/subtitle hierarchy, bounded wrapping and action spacing |
| Bottom navigation | Floating surface, 28 dp container and 18 dp selection; 24 dp icons; equal-width tabs |
| Tab labels | Same 12 sp typography, up to two lines, enough height to avoid clipped localized labels |
| Accessibility | Selected tab semantics, labelled controls, adequate touch targets, content grows with text size |
| Safe areas | Native host/app shell owns system insets once; common components add none |

An app may use a photographic catalogue hero, a mission list or a relay intake
form as appropriate. Their chrome, surfaces, spacing and controls still come
from this shared system. Product imagery and role-specific content are not
replaced with decorative or sample data.

For changes, run the existing app host checks and Android/iOS compilation. Then
review at least a primary tab, an operational form and account/auth state in each
app, including a narrow viewport and long translated labels. Confirm content is
measured between the header and navigation rather than hidden behind a fixed
overlay. Do not change role gates, credentials, provider configuration or
business policies as part of a design-only update.
