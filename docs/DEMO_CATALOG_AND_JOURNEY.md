# Separate online demo

This optional `demo` profile is distinct from `prod`, `staging` and the loopback-only local fixture system. It never enables the existing runtime payment simulator or local passwordless entry points.

- Dedicated Docker project `sequo-demo-v1`, PostgreSQL container/volume/database and database account.
- Independent random JWT/encryption secrets and JWT issuer/audience; no production records or secrets are copied.
- Demo startup rejects mixed profiles, any ordinary database URL/user/token realm, provider credentials, outgoing notification/email workers and local fixture access.
- Seeding requires zero users initially and retains all edits/history on subsequent restarts. It creates one customer, twelve store owners, four riders and one operations identity, twelve shops and 120 labelled products/images.
- Historical catalog examples from schema migrations are hidden only in this fresh demo database.
- Ordinary API authorization still applies. Private-code sign-in creates the ordinary device-bound session for the fixed demo customer. The private code is generated on the server and never tracked in source or app binaries.
- Demo journey controls accept only that customer's own home-delivery checkouts containing `demo-v1-*` products. No arbitrary user, payment amount, merchant or external rider is accepted. Current state determines the next allowed step. Replayed requests do not advance another step.
- Synthetic payment evidence uses the stored server-owned checkout total and normal reconciliation; it never calls a wallet provider. Seller, assignment, rider, PIN and lifecycle services create real demo state transitions. No real delivery, payment, push, email or payout is executed.
- Demo image evidence is stored as `DEMO_ILLUSTRATION`. Camera verification remains false. Ordinary order processors reject that evidence; only the checkout service with the guarded demo bean accepts it.

## Catalog and practice

Clothing sizes/colours, EU shoes, children's sizes, meal portions/spice, kg/litre/metre portions, electronics and home essentials are included. Some last sizes are intentionally unavailable. JPEG cards show product/shop names and unique item codes; these are code-generated text graphics.

Use the separate **Sequo Demo** app and its private sign-in code. Review a basket against the prefilled fictional address, submit, then use **Orders** buttons for payment → acceptance → preparation → ready → assignment → rider acceptance → pickup → delivery. The same normal order tracking records update after each step. Start with one shop. Guided home delivery supports up to four shops, one rider per package; normal rider capacity rules remain enforced. Group consolidation, relay delivery and physical proof are separate normal workflows, not claimed as exercised by these controls.

## Deployment and verification

`scripts/demo/deploy.py` verifies the uploaded JAR checksum, creates private random settings if absent and starts the standalone project privately on host loopback port 8081. It does not touch the live Compose project. Its HTTPS origin is `https://api.sequoservice.com:8443`, routed by a separately validated Caddy listener.

`scripts/demo/smoke.py` runs against that private loopback service and checks all 120 images, private authentication, one measured order, one meal, a two-shop size/colour order, all eight steps and safe replays. It retains the three completed test orders as examples and writes only non-secret verification metadata.

Before publishing the demo listener, check the production API remains healthy. Retain the prior Caddy configuration for rollback. Stopping only the `sequo-demo-v1` Compose project and removing its extra Caddy listener disables the demo without touching live data. Preserve the demo volume if retaining practice history. No automatic data reset is provided.
