# Admin product photos and publication

The signed MVP clauses 21, 23–24 and 116 cover SEQUO catalog editing, product photographs and approved generic images for standard products. The admin catalog already supported create/edit/archive; media preparation and upload previously required a merchant session. The admin media panel closes that gap without impersonating a merchant.

An authenticated `ADMIN` or `SUPER_ADMIN` can read an existing product, photograph a seller-specific product with the browser camera, approve a JPEG for a standard `GenericSealedItem`, or choose the optional configured standard image. A generic upload never sets camera verification. Product ownership is loaded from the product record; the media request cannot reassign a merchant.

## Operator flow

1. Open Catalog in the admin workspace. Read the existing product ID in **Product photos and publication**.
2. For a seller-specific product, start the live camera, photograph the product, enter a reason and upload the capture. Camera denial has a readable error and sends no upload.
3. For a standard product, choose a JPEG and explicitly confirm SEQUO approval of that image for the product. Enter a reason and approve/upload it. This does not require an external image host. If `sequo.catalog.approved-generic-image-url` contains an approved HTTPS URL, the existing `standard` reference is also available.
4. Review the server readback. Publishing/deactivating is a separate confirmed action and remains subject to the server photo policy.
5. Expand history to inspect the authenticated actor, reason, action and server timestamp. Public image reads succeed only while the product is active.

JPEG uploads are limited to 8 MiB and 12 million pixels. Both paths decode/re-encode the image, remove original metadata and store the actual JPEG in the existing product payload table. The authorized media read returns a JPEG preview with its longest side at most 256 pixels; draft images do not become public.

## Existing contracts and compatibility

All new routes are under `/api/admin/web/catalog/products/{productId}/media`:

| Method and suffix | Accepted data |
| --- | --- |
| `GET` | Current product, optional configured generic URL, bounded preview and last 50 admin media/publication changes |
| `POST /photos` | Existing `GenericCatalogReference` / `standard` reference, plus reason, exact `expectedUpdatedAt`, `idempotencyKey` |
| `POST /photos/camera` | Multipart JPEG, `capturedAt`, `deviceId`, reason, exact revision and key |
| `POST /photos/generic` | Multipart JPEG, `genericApproved=true`, reason, exact revision and key |
| `POST /publication` | `status=ACTIVE` or `INACTIVE`, reason, exact revision and key |

New media writes require a current active administrator in the database. Product revision checks and a stable retry identity prevent overwriting a reviewed newer record. Exact replay returns current persisted state and adds no second event; changed intent with the same key conflicts. Payload, product state and audit history share one transaction.

After a lost response, the panel clears success claims and retains the original request in memory for an identical retry. Refresh/review explicitly discards that local retry. Navigation or browser reload also discards captured bytes; inspect current server state before starting another action. Images and credentials are not stored in browser session/local storage by the panel.

Migration `V60__admin_catalog_media_history.sql` adds only audit storage. It does not rewrite existing products/photos. Legacy products remain readable. New admin-created seller-specific products now follow the same photo-required default as merchant-created ones. Direct create/update `cameraVerified` or capture-label claims are ignored; validated upload owns those fields. Existing merchant upload/preparation routes and customer image URLs are retained.

Only the exact merchant-camera, admin-camera and admin-generic multipart routes are admitted by the content-type filter; unrelated API bodies still require JSON. Normal runtime configuration must retain `spring.servlet.multipart.max-file-size=8MB` and `spring.servlet.multipart.max-request-size=9MB`, including the sole-config local workspace.

## Verification and limits

Focused HTTP checks cover role denials, actual stored JPEG readback/public visibility, invalid bytes/type/stale camera capture, photo-required publication, preset references, explicit generic approval, exact replay, stale revision and recorded actor history. Existing merchant catalog and content-type filter regressions also run.

The isolated browser check is `scripts/product-media-browser-check.cjs` in `sequo-admin`; it uses synthetic canvas video and local mocked HTTP, never a physical camera or real account. It verifies camera denial/capture, track cleanup, multipart fields, lost-acknowledgement retry with identical image bytes, generic approval, publication/readback and failed-read clearing. Real device camera permission/capture and deployment-origin browser behavior remain device/staging checks. The existing server validates image bytes and capture metadata; it does not provide cryptographic hardware-camera attestation.
