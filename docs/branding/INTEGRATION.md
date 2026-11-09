# SEQUO brand integration

The existing folded S package direction is installed in the local Customer,
Vendor, Hub and Rider KMP projects, and the Admin, Developers and Services web
projects. The alternative concepts are not used. Changes are local source and
resource updates; no website deployment or store submission was performed.

## Shared source and exports

`scripts/sequo-brand.py` contains the master symbol and outlined wordmark.
`scripts/sequo-brand-rollout.py` generates platform assets, stages source changes,
and installs them into the seven sibling repositories. `platform-assets/` holds
the generated SVG/PNG/ICO masters. `installation.json` records every installed
path and its SHA-256 hash, including the previous hash for replaced files.

The dark-surface version uses existing pale mint theme colors to keep the box
visible. Light-surface exports retain the original green front and mint lid.
White monochrome paths are available for notifications and themed launchers.
All lettering and V/H/R role badges are paths; fonts are not required.

## KMP apps

Each app contains these resources under
`shared/src/commonMain/composeResources/drawable/`:

- `sequo_brand_symbol.png`, `sequo_brand_symbol_dark.png`
- `sequo_brand_logo_light.png`, `sequo_brand_logo_dark.png`
- `sequo_brand_wordmark_light.png`, `sequo_brand_wordmark_dark.png`
- `sequo_brand_stacked_light.png`, `sequo_brand_stacked_dark.png`

Corresponding SVG masters live under `composeResources/files/brand/logos/`.
The PNGs are deliberately used in Compose to work on both Android and iOS
without an SVG decoder dependency. Unused legacy resource names and their imports have been removed; app code uses
only the current `sequo_brand_*` resource family.

`dev.orestegabo.sequo.design.SequoBrand.kt` provides theme-aware painters:

```kotlin
Image(sequoBrandSymbolPainter(), contentDescription = "SEQUO")
Image(sequoBrandLogoPainter(), contentDescription = "SEQUO")
Image(sequoBrandLogoPainter(stacked = true), contentDescription = "SEQUO")
```

The helpers choose colors from the current Material theme's surface luminance,
so explicit app appearance settings work independently of the OS appearance.
Auth, splash and existing brand-mark placements use these helpers. The Rider
splash now includes the full logo. Existing app names, subtitles and workflows
are preserved.

## Android

Each app has legacy launcher PNGs at mdpi through xxxhdpi, circular legacy
icons, a vector adaptive foreground and a solid warm-white background.
`drawable/ic_brand_foreground.xml` supplies the adaptive artwork;
`drawable/ic_brand_monochrome.xml` supplies a safely inset themed icon;
`drawable/ic_stat_sequo.xml` supplies the white-alpha notification symbol.
Customer notifications now use this dedicated small icon instead of a colored
launcher bitmap. Existing Android 12 splash references point to the new vector.

The adaptive foreground fits within the central 66dp safe circle of the 108dp
canvas. It includes no baked mask. Vendor, Hub and Rider carry small V/H/R
badges on their app tiles; the Customer icon remains unbadged. The base symbol
used inside screens is shared across all four apps.

## iOS

`iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/` contains the generated icons
and catalog references. Existing size slots are preserved; all apps include
dark and tinted 1024px appearances. App icons are opaque RGB squares without
baked rounded corners, allowing iOS to apply the platform mask.

## Web

Each website serves the identity from `public/brand/`. Admin login/sidebar,
Developer navigation (including its collapsed state), and Services header,
phone preview and footer use the new vectors. The three HTML entry points
reference the SVG favicon, multi-size ICO fallback, 180px Apple touch icon and
`site.webmanifest`. The manifest supplies 192/512px standard and maskable icons;
its display mode remains `browser`. Unused legacy public logo PNG copies have been removed. No service worker or offline behavior is introduced.

## Regeneration

From `sequo-api`, with librsvg and Pillow available:

```sh
/opt/homebrew/bin/python3 scripts/sequo-brand-rollout.py
# Inspect build/branding-rollout/plan.json and the staged files, then:
/opt/homebrew/bin/python3 scripts/sequo-brand-rollout.py --apply
```

Preparation writes only inside `sequo-api`. Installation writes to sibling
projects and verifies source hashes before applying. The source patches are
idempotent and use the current files; old-source backups are no longer retained
or required. Repeat generation retains the current source changes without
reintroducing legacy aliases, duplicate CSS or duplicate HTML icon links.

Rejected concepts, archived revisions, obsolete preview sheets, unused legacy
KMP branding resources, old Android foregrounds, unused web logo PNGs and
old-source backups have been deleted. The cleanup checks source references and
file hashes before deleting resources. Its exact inventory is available locally
under `build/brand-cleanup/plan.json`. Regeneration retains only current assets.

## Verification

On 9 November 2026:

- All four `:androidApp:assembleDebug` builds passed, including Android resource
  compilation and the new Compose resource accessors.
- All four `:shared:compileKotlinIosSimulatorArm64` compilations passed.
- Apple's `actool` compiled all four AppIcon catalogs successfully.
- All three `npm run build` commands passed, including Vue/TypeScript checks.
- Isolated headless Chrome checked the built websites at 1440px and 390px,
  confirming loaded brand images, no horizontal overflow and a visible symbol
  in collapsed Developer navigation. Screenshots and results are under
  `previews/web/`. Reproduce with `node scripts/check-sequo-brand-web.cjs`.
- iOS opacity/dimensions and adaptive-icon safe radii were checked; installation
  hashes and `git diff --check` passed across all seven repositories.

The updated Customer Android app was also installed alongside existing fixture
apps and its actual authentication screen was captured under `previews/app/`.
No account sign-in was performed. These checks do not claim a physical-device
session or a full signed iOS app build. Existing compiler deprecation warnings are unrelated to the artwork.

After the old-version cleanup, all three websites were rebuilt and rechecked in
Chrome. Android build outputs were cleaned to remove cached obsolete logo files,
then rebuilt; package contents were checked for retired logo names.
