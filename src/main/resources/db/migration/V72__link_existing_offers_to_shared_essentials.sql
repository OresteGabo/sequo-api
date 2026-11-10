-- Reviewed existing offer -> shared essential links. No fuzzy ingredient matching.
-- Keep legacy reference IDs as redirects so cached drafts/older clients remain valid.
ALTER TABLE catalog_product_references ADD COLUMN canonical_reference_id VARCHAR(128);
ALTER TABLE catalog_product_references ADD CONSTRAINT fk_catalog_reference_canonical
 FOREIGN KEY (canonical_reference_id) REFERENCES catalog_product_references(id);
ALTER TABLE catalog_product_references ADD CONSTRAINT chk_catalog_reference_not_self
 CHECK (canonical_reference_id IS NULL OR canonical_reference_id <> id);
CREATE INDEX idx_catalog_reference_canonical ON catalog_product_references(canonical_reference_id);

INSERT INTO catalog_product_references(id,name,category,aliases,essential) VALUES
 ('ref-apple','Pomme','grocery','apple|apples|pommes',TRUE),
 ('ref-avocado','Avocat','grocery','avocado|avocados|avocats',TRUE),
 ('ref-eggplant','Aubergine','grocery','eggplant|eggplants|aubergines',TRUE)
ON CONFLICT (id) DO NOTHING;

-- The coconut family already exists under its stable full-name reference. Reuse it.
UPDATE catalog_product_references SET essential=TRUE,
 aliases=concat_ws('|',NULLIF(aliases,''),'coconut|coconuts|noix de coco')
WHERE id='ref-custom-dc6790f6fbd26ba0cc361dc8fdbda307' AND name='Noix de coco' AND canonical_reference_id IS NULL;

CREATE TEMP TABLE reviewed_essential_links(product_id TEXT,expected_name TEXT,old_reference_id TEXT,new_reference_id TEXT) ON COMMIT DROP;
INSERT INTO reviewed_essential_links VALUES
 ('lait-frais-1l','Lait frais 1L','ref-custom-8f6ddf87df58dd68b804faa3a9a8aca0','ref-milk'),
 ('demo-v1-marche-04','[TEST] Carottes','ref-custom-3a1488b2d0072385419db72bbdbf0177','ref-carrot'),
 ('demo-v1-grains-10','[TEST] Farine de mais','ref-custom-81094ab5f2283adcd8af801eee35a2c0','ref-flour'),
 ('demo-v1-grains-04','[TEST] Haricots blancs','ref-custom-6912c05c779cc1665e19586ced420ddf','ref-beans'),
 ('demo-v1-grains-03','[TEST] Haricots rouges','ref-custom-00c700a058e0e84b574673b49d05ea30','ref-beans'),
 ('demo-v1-boissons-06','[TEST] Huile arachide','ref-custom-7d529c0fe96169add16b4bc63bb21023','ref-oil'),
 ('demo-v1-boissons-07','[TEST] Huile tournesol','ref-custom-6eeec6a574df98a2f5dda3cb52f11d06','ref-oil'),
 ('demo-v1-grains-05','[TEST] Lentilles vertes','ref-custom-40accfffc693383bb5d1736d207f5c21','ref-lentils'),
 ('demo-v1-grains-06','[TEST] Mais sec','ref-custom-51f03ab8426c21eafe7e25bf52717a5d','ref-maize'),
 ('demo-v1-marche-08','[TEST] Mangues','ref-custom-5ff30537d413e9a47392b7a189e2d134','ref-mango'),
 ('demo-v1-grains-07','[TEST] Mil dore','ref-custom-063b20dbf53d9fcfc4174a1742a8d381','ref-millet'),
 ('demo-v1-marche-06','[TEST] Oranges','ref-custom-652af7d187cb5d780d5de17f64fba845','ref-orange'),
 ('demo-v1-grains-09','[TEST] Pois chiches','ref-custom-7d157ec9e381d82aed66763d3073270c','ref-chickpeas'),
 ('demo-v1-marche-03','[TEST] Pommes de terre','ref-custom-0d9ad5ec92b40dac308b04ac402d61bb','ref-potato'),
 ('demo-v1-grains-02','[TEST] Riz parfume','ref-custom-d763353b429e263f61ca2fa7fed6a346','ref-rice'),
 ('demo-v1-grains-08','[TEST] Sorgho rouge','ref-custom-9a75b6ad455980712f531b6c92e613a1','ref-sorghum'),
 ('demo-v1-marche-07','[TEST] Pommes','ref-custom-f2d0b287a558d6898eb2b096aa2474b6','ref-apple'),
 ('demo-v1-marche-09','[TEST] Avocats','ref-custom-c9bd48121b28474d1f2dacc844b7174a','ref-avocado'),
 ('demo-v1-marche-10','[TEST] Aubergines','ref-custom-48d6b72ca1a6ad822628db05b911c32e','ref-eggplant');

-- Guards preserve any listing that a vendor reclassified/renamed since the review.
CREATE TEMP TABLE applied_essential_links ON COMMIT DROP AS
 SELECT p.id AS product_id,m.old_reference_id,m.new_reference_id
 FROM products p JOIN reviewed_essential_links m ON p.id=m.product_id
 JOIN catalog_product_references r ON r.id=m.new_reference_id AND r.essential=TRUE AND r.canonical_reference_id IS NULL
 WHERE p.category='grocery' AND p.name=m.expected_name
 AND p.product_reference_id=m.old_reference_id;
UPDATE products p SET product_reference_id=m.new_reference_id,updated_at=CURRENT_TIMESTAMP,version=p.version+1
FROM applied_essential_links m WHERE p.id=m.product_id;

-- Redirect only obsolete full-name references that no remaining offer uses.
UPDATE catalog_product_references old SET canonical_reference_id=m.new_reference_id
FROM (SELECT DISTINCT old_reference_id,new_reference_id FROM applied_essential_links) m
WHERE old.id=m.old_reference_id AND old.essential=FALSE AND old.canonical_reference_id IS NULL
 AND NOT EXISTS (SELECT 1 FROM products p WHERE p.product_reference_id=old.id);

-- Preserve discoverability of reviewed variants while returning one shared suggestion.
WITH legacy_names AS (
 SELECT canonical_reference_id,array_agg(regexp_replace(name,'^\[TEST\]\s*','')) AS names
 FROM catalog_product_references WHERE canonical_reference_id IS NOT NULL GROUP BY canonical_reference_id
), aliases AS (
 SELECT r.id,string_agg(DISTINCT trim(term),'|' ORDER BY trim(term)) AS terms
 FROM catalog_product_references r JOIN legacy_names n ON n.canonical_reference_id=r.id
 CROSS JOIN LATERAL unnest(string_to_array(r.aliases,'|') || n.names) term
 WHERE trim(term)<>'' GROUP BY r.id
)
UPDATE catalog_product_references r SET aliases=a.terms FROM aliases a WHERE r.id=a.id;

-- Deliberately excluded: prepared meals, juice/syrup, litre-sold ginger drink,
-- mixed tomato/onion baskets, and non-grocery goods. Offer IDs/prices/options,
-- cart lines and historical order snapshots remain unchanged.
