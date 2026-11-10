# Shared product references

A reference identifies a product family across shops. The seller's product ID remains the purchasable offer: prices, photos, brand/pack details, stock, options, negotiations and cart/order ownership remain separate. “Lait” does not assert that every linked brand or pack size is interchangeable.

`GET /api/catalog/product-references?q=lai&limit=6` returns a bounded list of `{id,name,category,aliases}`. Queries require at least two normalized characters; exact aliases rank above name/word prefixes. French accents/case are normalized. The endpoint is public and read-only, with at most 12 results and a 180-character query limit.

Merchant create/patch product requests accept `productReferenceId`. A nonempty ID must exist. Empty string explicitly unlinks. Omitted/null on an unchanged product preserves its link; changing its name through an older client recalculates the reference rather than retaining a stale association. Existing merchant ownership checks apply.

Without an explicit choice, a full-name exact alias is reused only when unambiguous. Otherwise the complete normalized name creates/reuses a deterministic shared reference, using an atomic PostgreSQL insert. Prefix/fuzzy matches are suggestions, never automatic identity decisions. Explicit unlink suppresses automatic linking. Unknown one-character names stay unlinked. Vendors cannot overwrite a shared reference's aliases, name or category through product edits.

Migration V70 adds the reference table and nullable indexed foreign key. It seeds 27 French/English common families, conservatively links exact known aliases, then groups remaining existing offers only by complete normalized names. It does not change offer IDs/prices/photos/publication/options or past order snapshots. Curated synonyms can be expanded through reviewed catalog maintenance; newly seen full names are reusable immediately. The API includes `productReference` in product responses. Related products prioritize the same reference before category similarity.

SequoVendor debounces suggestions, shows the selected reference with Unlink, fills an empty category on selection and allows saving through suggestion outages. The vendor may customize the listing name/details while retaining an explicitly selected family. Sequo searches the reference's name/aliases alongside seller text and groups matching offers across shops. Each offer still opens and orders from its original shop.

Validation covers alias ranking, accents, explicit linking/unlinking, rejected unknown IDs, foreign-owner refusal, distinct seller prices/IDs, full-name reuse, renamed products and customer search without offer collapsing. Raw deployment credentials/database copies are not committed.


## Presaved essential goods

V71 expands the reviewed essentials to 70 shared families and adds `GET /api/catalog/product-references/essentials`. This read-only endpoint returns only curated essentials, not every merchant-created name. The picker offers Food & groceries, Household, Personal care and Baby categories, with French/English alias search. See [the complete presaved inventory](ESSENTIAL_PRODUCTS.md).

Select **Choose from essentials** before entering a new Vendor listing. Selecting Lait fills a blank name/category and sends the existing `ref-milk` ID on save. Changing a previously prefilled choice updates its name/category; manually customized offer text is retained. No price, stock, media or selling-unit option is copied from another shop. Existing product name suggestions and custom products remain available.

All shops choosing Lait reuse the same canonical row: `catalog_product_references.id = ref-milk`. Each distinct shop offer has its own `products.id` and `products.product_reference_id = ref-milk`, enforced by the existing database foreign key. New predefined names reuse an existing full-name reference when available; V71 does not duplicate one family per merchant, alter seller offers, or rewrite historical orders.
