# Reviewed links for existing essentials

Existing seller offers retain their own names, prices, sizes/amounts, photos and stock. This backfill changes their shared-family foreign key. Four missing produce families (apples, avocados, aubergines, coconuts) extend the picker from 70 to 74 essentials; the coconut reference is reused in place.

| Existing offer | Shared essential |
| --- | --- |
| Lait frais 1L | Lait |
| [TEST] Carottes | Carotte |
| [TEST] Farine de mais | Farine |
| [TEST] Haricots blancs | Haricots |
| [TEST] Haricots rouges | Haricots |
| [TEST] Huile arachide | Huile |
| [TEST] Huile tournesol | Huile |
| [TEST] Lentilles vertes | Lentilles |
| [TEST] Mais sec | Maïs |
| [TEST] Mangues | Mangue |
| [TEST] Mil dore | Mil |
| [TEST] Oranges | Orange |
| [TEST] Pois chiches | Pois chiches |
| [TEST] Pommes de terre | Pomme de terre |
| [TEST] Riz parfume | Riz |
| [TEST] Sorgho rouge | Sorgho |
| [TEST] Pommes | Pomme |
| [TEST] Avocats | Avocat |
| [TEST] Aubergines | Aubergine |

Already linked grocery offers (white rice, tomatoes, onions, bananas) retain their identities. Noix de coco retains its existing ID and becomes a curated essential. The result is 24 grocery offers linked to essentials, including one currently available milk offer. A search cannot create milk offers from other shops; vendors must list them before cross-shop milk prices exist.

Ginger sold by the litre in the drinks shop is treated as a beverage, not raw ginger. Juices/syrups do not become their source fruit, prepared meals do not become raw rice/chicken/vegetables, and the mixed tomato/onion basket retains its own identity. Non-grocery listings, including a fabric listing categorized as grocery, are not reclassified by this task.

Obsolete full-name reference IDs remain as redirects for cached clients/drafts. Suggestions return the canonical family; old IDs and exact old names resolve to that same family on save. Historical orders and cart product IDs remain intact. The backfill is guarded by reviewed product ID, name, category and previous reference, so a vendor change is not silently overwritten.
