# SEQUO folded S parcel logo

The package itself forms the S. Its broad front wall follows a continuous
geometric S contour: the upper return, middle fold and lower return are the
material of the box. Two transparent channels open into the outside silhouette;
the upper channel continues around the right-hand side. The lid and side planes
supply the parcel perspective. There is no letter printed, overlaid or enclosed
on a complete rectangular face.

The folded-box identity is now integrated into the four local KMP app projects
and three web projects. See [integration and verification](INTEGRATION.md) for
platform exports, resource paths and rebuild instructions. UI theme tokens are
unchanged. No website deployment or store submission was performed. Only the current identity and its required platform variants are retained.
Rejected concepts, archived revisions and unused legacy files have been removed.

| Asset | Use |
| --- | --- |
| `sequo-logo-horizontal.svg` | Green folded parcel and white path wordmark; transparent, for dark surfaces |
| `sequo-logo-light.svg` | Green folded parcel and dark-green path wordmark; transparent, for light surfaces |
| `sequo-symbol.svg` | Standalone folded S parcel; transparent |
| `sequo-logo-monochrome.svg` | Identical physical silhouette in a single white ink with open transparent channels |
| `sequo-app-icon.svg` | Folded symbol in a warm off-white rounded square |
| `sequo-favicon.svg` | Same geometry enlarged within the tile for 16/32px use |

## Palette

These colors already appear in the canonical mobile design system at
`../Sequo/shared/src/commonMain/kotlin/dev/orestegabo/sequo/design/SequoDesignSystem.kt`.

| Hex | Use |
| --- | --- |
| `#0B6B46` | S-shaped front wall; existing primary |
| `#087F4F` | Folded side wall; existing secondary |
| `#7AD8A9` | Lid; existing dark primary / inverse primary |
| `#002C1C` | Light-version wordmark and dark preview surface; existing on primary container |
| `#F7F8F4` | App/favicon tile; existing background |
| `#FFFFFF` | Dark-version wordmark and monochrome silhouette |

## Geometry and typography

The front S contour is one closed path, transformed onto the package plane with
`matrix(1 .25 0 1 72 170)`. The side wall repeats the upper opening at the same
height and depth. The mint lid shares its outer edge with the S's top stroke.
Removing the shading still leaves the physical S silhouette in monochrome.
All gaps are transparent geometry, not background-colored patches.

The wordmark is now custom uppercase SEQUO geometry, rather than the previous
rounded lettering. Its S repeats the package wall's open contour; the other
letters use the same 44-unit stroke module. The E continues the open-channel
motif, and the Q, U and O use folded corner cuts. The Q tail is part of its outer
contour rather than an applied ornament. Each glyph is an individual closed
vector path with optical spacing. No font is used or required; there is no
font licence dependency. The six assets include accessible titles and use no
raster images, external references, filters or gradients.

Horizontal viewBox: `24 40 1622 448`. Symbol viewBox: `24 40 464 448`.
App/favicon viewBox: `0 0 512 512`. The favicon increases symbol scale by 10%;
its shape is otherwise identical to the app icon.

## Build and validation

```sh
python3 scripts/sequo-brand.py --render
```

The builder uses Python's standard library. Preview rendering additionally
requires `rsvg-convert` from librsvg as a development tool. Without `--render`,
the command builds and checks the six SVGs only. It does not edit production
resources or archived directions.

`previews/` contains six individual variant PNGs, a combined review sheet,
transparent symbol PNGs at 24, 48, 96 and 192px, favicon PNGs at 16 and 32px,
and full-logo previews at 200, 320 and 640px widths.
Validation includes XML parsing, titles/viewBoxes, vector-only geometry and no
external resources; successful librsvg rendering; visual review; and raster
bounds/transparency checks in `previews/validation.json`.

This revision follows the request for an S that forms the physical shape of
the box, superseding the earlier printed-letter and floating-ribbon concepts.

## Choosing a lockup

Use `sequo-symbol.svg` for compact navigation, avatars and other places where
SEQUO is already identified. Use the full horizontal logo for website headers,
sign-in screens and branded documents. The symbol, app icon and favicon share the refined folded-box geometry. The wordmark is designed to
share its visual construction with the icon while leaving each independently
recognizable. Prefer at least 200px width for the full lockup and at least 24px
for the standalone symbol; use the dedicated favicon at 16px.

## Box proportion refinement

The front plane is now 224 by 216 units and its depth is the vector (+144, -84).
All opposing lid edges are parallel and all side-channel heights match their
front-face counterparts. Previously the rear lid corner did not follow the
front plane, producing an uneven top face. The S now has equal 60-unit end
spines, 44-unit material bands and 42-unit openings. Six-unit internal corner
curves soften the turns while keeping the S's open silhouette. The centered
icon uses the refined 86–442 vertical bounds. Colors and wordmark geometry are
unchanged in this refinement. Retired comparison previews have been removed.
