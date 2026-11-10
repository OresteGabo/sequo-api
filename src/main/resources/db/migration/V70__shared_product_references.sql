-- Shared product families: seller listings and their prices/options/stock remain independent.
CREATE TABLE catalog_product_references (
    id VARCHAR(128) PRIMARY KEY,
    name VARCHAR(180) NOT NULL,
    category VARCHAR(128) NOT NULL,
    aliases TEXT NOT NULL
);
ALTER TABLE products ADD COLUMN product_reference_id VARCHAR(128);
ALTER TABLE products ADD CONSTRAINT fk_products_shared_reference FOREIGN KEY (product_reference_id) REFERENCES catalog_product_references(id);
CREATE INDEX idx_products_shared_reference ON products(product_reference_id);
INSERT INTO catalog_product_references(id,name,category,aliases) VALUES ('ref-milk','Lait','grocery','milk|laits');
INSERT INTO catalog_product_references(id,name,category,aliases) VALUES ('ref-rice','Riz','grocery','rice|riz blanc|white rice');
INSERT INTO catalog_product_references(id,name,category,aliases) VALUES ('ref-beans','Haricots','grocery','beans|haricot');
INSERT INTO catalog_product_references(id,name,category,aliases) VALUES ('ref-maize','Maïs','grocery','maize|corn|mais');
INSERT INTO catalog_product_references(id,name,category,aliases) VALUES ('ref-flour','Farine','grocery','flour');
INSERT INTO catalog_product_references(id,name,category,aliases) VALUES ('ref-sugar','Sucre','grocery','sugar');
INSERT INTO catalog_product_references(id,name,category,aliases) VALUES ('ref-salt','Sel','grocery','salt');
INSERT INTO catalog_product_references(id,name,category,aliases) VALUES ('ref-oil','Huile','grocery','oil|cooking oil|huile de cuisine');
INSERT INTO catalog_product_references(id,name,category,aliases) VALUES ('ref-water','Eau','grocery','water|eau minerale|mineral water');
INSERT INTO catalog_product_references(id,name,category,aliases) VALUES ('ref-coffee','Café','grocery','coffee|cafe');
INSERT INTO catalog_product_references(id,name,category,aliases) VALUES ('ref-tea','Thé','grocery','tea|the');
INSERT INTO catalog_product_references(id,name,category,aliases) VALUES ('ref-bread','Pain','grocery','bread');
INSERT INTO catalog_product_references(id,name,category,aliases) VALUES ('ref-eggs','Œufs','grocery','eggs|oeufs|oeuf');
INSERT INTO catalog_product_references(id,name,category,aliases) VALUES ('ref-yogurt','Yaourt','grocery','yogurt|yoghurt|yaourts');
INSERT INTO catalog_product_references(id,name,category,aliases) VALUES ('ref-tomato','Tomate','grocery','tomato|tomatoes|tomates');
INSERT INTO catalog_product_references(id,name,category,aliases) VALUES ('ref-onion','Oignon','grocery','onion|onions|oignons');
INSERT INTO catalog_product_references(id,name,category,aliases) VALUES ('ref-banana','Banane','grocery','banana|bananas|bananes');
INSERT INTO catalog_product_references(id,name,category,aliases) VALUES ('ref-soap','Savon','home','soap|savons');
INSERT INTO catalog_product_references(id,name,category,aliases) VALUES ('ref-tshirt','T-shirt','fashion','t shirt|tshirt|t-shirts|tee shirt|t-shirt essentiel');
INSERT INTO catalog_product_references(id,name,category,aliases) VALUES ('ref-shirt','Chemise','fashion','shirt|chemises');
INSERT INTO catalog_product_references(id,name,category,aliases) VALUES ('ref-jeans','Jean','fashion','jeans');
INSERT INTO catalog_product_references(id,name,category,aliases) VALUES ('ref-sneakers','Baskets','fashion','sneakers|baskets urbaines');
INSERT INTO catalog_product_references(id,name,category,aliases) VALUES ('ref-sandals','Sandales','fashion','sandals|sandale');
INSERT INTO catalog_product_references(id,name,category,aliases) VALUES ('ref-dress','Robe','fashion','dress|robes');
INSERT INTO catalog_product_references(id,name,category,aliases) VALUES ('ref-phone','Téléphone','electronics','phone|telephone|smartphone');
INSERT INTO catalog_product_references(id,name,category,aliases) VALUES ('ref-headphones','Écouteurs','electronics','earphones|headphones|ecouteurs');
INSERT INTO catalog_product_references(id,name,category,aliases) VALUES ('ref-diapers','Couches','baby','diapers|nappies|couche');

-- Do not merge similar names, brands, sizes or seller-specific dishes automatically.
WITH names AS (
 SELECT id, lower(trim(term)) AS term
 FROM catalog_product_references CROSS JOIN LATERAL unnest(string_to_array(name || '|' || aliases, '|')) AS term
), unique_names AS (
 SELECT term, min(id) AS id FROM names GROUP BY term HAVING count(DISTINCT id)=1
)
UPDATE products p SET product_reference_id=n.id
FROM unique_names n
WHERE lower(trim(regexp_replace(p.name, '^\[TEST\] ', ''))) = n.term;

-- Give all remaining existing offers a reference based only on their complete normalized name.
-- Explicit test labels are retained here; reference creation does not publish or change offers.
WITH normalized AS (
 SELECT name, category,
 trim(regexp_replace(regexp_replace(
 translate(replace(replace(lower(name),'œ','oe'),'æ','ae'),
 'àâäáãåçéèêëîïíìôöóòõùûüúÿý', 'aaaaaaceeeeiiiiooooouuuuyy'),
 '[^[:alnum:]]+', ' ', 'g'), '\s+', ' ', 'g')) AS key
 FROM products WHERE product_reference_id IS NULL
), grouped AS (
 SELECT key, min(name) AS name, coalesce(min(category),'other') AS category
 FROM normalized WHERE length(key)>=2 GROUP BY key
)
INSERT INTO catalog_product_references(id,name,category,aliases)
SELECT 'ref-custom-' || md5(key),name,category,'' FROM grouped ON CONFLICT DO NOTHING;

UPDATE products p SET product_reference_id=r.id
FROM catalog_product_references r
WHERE p.product_reference_id IS NULL AND r.id = 'ref-custom-' || md5(
 trim(regexp_replace(regexp_replace(
 translate(replace(replace(lower(p.name),'œ','oe'),'æ','ae'),
 'àâäáãåçéèêëîïíìôöóòõùûüúÿý', 'aaaaaaceeeeiiiiooooouuuuyy'),
 '[^[:alnum:]]+', ' ', 'g'), '\s+', ' ', 'g')));
