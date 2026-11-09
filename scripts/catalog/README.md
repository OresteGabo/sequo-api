# Owner-requested live test catalog import

`publish_requested_test_catalog_20261109.py` records the one-time import explicitly requested by the owner on 9 November 2026. It is not a startup seed or a reusable reset command. It refuses occupied IDs and an existing backup. It was executed after a complete live database backup and a rollback-only insert validation.

It imported only 16 fictional merchant/rider identities, their normal roles/memberships, 12 stores, 120 products, choice groups/options, storefront metadata and four rider records. It did not copy demo customer/admin identities, sessions, orders, payment evidence or settlement records. Product images are labelled illustrations with camera verification false.

The normal API uses its existing database and payment rules. Wallet adapters are still disconnected; required photo validation is unchanged. No production payment simulator was enabled.

Imported IDs and later cleanup guidance are maintained in the customer app repository at `demo/live-catalog/`. Backups and Caddy rollback files remain private on the server under `/home/ubuntu/sequo-live-test-catalog-20261109/`. The original isolated demo containers are stopped; their volume is retained.
