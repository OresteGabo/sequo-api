-- Curated essentials are shared product families, not seller offers or stock rows.
ALTER TABLE catalog_product_references ADD COLUMN essential BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE catalog_product_references SET essential=TRUE WHERE id IN (
 'ref-milk','ref-rice','ref-beans','ref-maize','ref-flour','ref-sugar','ref-salt','ref-oil',
 'ref-water','ref-coffee','ref-tea','ref-bread','ref-eggs','ref-yogurt','ref-tomato','ref-onion',
 'ref-banana','ref-soap','ref-diapers');

WITH seeds(id,name,category,aliases,normalized_name) AS (VALUES
 ('ref-lentils','Lentilles','grocery','lentil|lentils|lentille','lentilles'),
 ('ref-chickpeas','Pois chiches','grocery','chickpea|chickpeas|pois chiche','pois chiches'),
 ('ref-millet','Mil','grocery','millet','mil'),
 ('ref-sorghum','Sorgho','grocery','sorghum','sorgho'),
 ('ref-fonio','Fonio','grocery','fonio grain','fonio'),
 ('ref-cassava','Manioc','grocery','cassava|maniocs','manioc'),
 ('ref-yam','Igname','grocery','yam|yams|ignames','igname'),
 ('ref-sweet-potato','Patate douce','grocery','sweet potato|sweet potatoes|patates douces','patate douce'),
 ('ref-potato','Pomme de terre','grocery','potato|potatoes|pommes de terre','pomme de terre'),
 ('ref-plantain','Banane plantain','grocery','plantain|plantains|bananes plantains','banane plantain'),
 ('ref-pasta','Pâtes','grocery','pasta|pates|macaroni|spaghetti','pates'),
 ('ref-oats','Avoine','grocery','oat|oats|flocons avoine','avoine'),
 ('ref-semolina','Semoule','grocery','semolina|semoules','semoule'),
 ('ref-peanuts','Arachides','grocery','peanut|peanuts|arachide','arachides'),
 ('ref-soybeans','Soja','grocery','soy|soya|soybean|soybeans','soja'),
 ('ref-gari','Gari','grocery','garri|cassava granules','gari'),
 ('ref-tapioca','Tapioca','grocery','tapioca pearls','tapioca'),
 ('ref-cheese','Fromage','grocery','cheese|fromages','fromage'),
 ('ref-butter','Beurre','grocery','butter','beurre'),
 ('ref-cream','Crème','grocery','cream|creme|creme fraiche','creme'),
 ('ref-beef','Bœuf','grocery','beef|boeuf|viande de boeuf','boeuf'),
 ('ref-chicken','Poulet','grocery','chicken|poulets','poulet'),
 ('ref-fish','Poisson','grocery','fish|poissons','poisson'),
 ('ref-sardines','Sardines','grocery','sardine','sardines'),
 ('ref-carrot','Carotte','grocery','carrot|carrots|carottes','carotte'),
 ('ref-cabbage','Chou','grocery','cabbage|cabbages|choux','chou'),
 ('ref-lettuce','Laitue','grocery','lettuce|lettuces|laitues','laitue'),
 ('ref-okra','Gombo','grocery','okra|gombos','gombo'),
 ('ref-bell-pepper','Poivron','grocery','bell pepper|bell peppers|poivrons','poivron'),
 ('ref-chili','Piment','grocery','chili|chilli|chillies|piments','piment'),
 ('ref-garlic','Ail','grocery','garlic','ail'),
 ('ref-ginger','Gingembre','grocery','ginger','gingembre'),
 ('ref-lemon','Citron','grocery','lemon|lemons|citrons','citron'),
 ('ref-orange','Orange','grocery','oranges','orange'),
 ('ref-mango','Mangue','grocery','mango|mangoes|mangues','mangue'),
 ('ref-pineapple','Ananas','grocery','pineapple|pineapples','ananas'),
 ('ref-tomato-paste','Concentré de tomate','grocery','tomato paste|concentre de tomate|concentre de tomates','concentre de tomate'),
 ('ref-stock-cubes','Cube de bouillon','grocery','stock cube|stock cubes|bouillon cube|cubes de bouillon','cube de bouillon'),
 ('ref-laundry-detergent','Lessive','home','laundry detergent|washing powder','lessive'),
 ('ref-dish-soap','Liquide vaisselle','home','dish soap|dishwashing liquid','liquide vaisselle'),
 ('ref-bleach','Eau de Javel','home','bleach|javel','eau de javel'),
 ('ref-toilet-paper','Papier toilette','home','toilet paper|papier hygienique','papier toilette'),
 ('ref-paper-towels','Essuie-tout','home','paper towel|paper towels|essuie tout','essuie tout'),
 ('ref-trash-bags','Sacs poubelle','home','trash bags|garbage bags|sac poubelle','sacs poubelle'),
 ('ref-matches','Allumettes','home','match|matches|allumette','allumettes'),
 ('ref-mosquito-net','Moustiquaire','home','mosquito net|mosquito nets|moustiquaires','moustiquaire'),
 ('ref-toothpaste','Dentifrice','beauty','toothpaste|dentifrices','dentifrice'),
 ('ref-toothbrush','Brosse à dents','beauty','toothbrush|toothbrushes|brosses a dents','brosse a dents'),
 ('ref-sanitary-pads','Serviettes hygiéniques','beauty','sanitary pads|sanitary towels|serviette hygienique','serviettes hygieniques'),
 ('ref-shampoo','Shampooing','beauty','shampoo|shampoing','shampooing'),
 ('ref-baby-wipes','Lingettes bébé','baby','baby wipes|lingettes bebe','lingettes bebe')
), resolved AS (
 SELECT s.*, COALESCE((SELECT r.id FROM catalog_product_references r
 WHERE trim(regexp_replace(translate(replace(replace(lower(r.name),'œ','oe'),'æ','ae'),
 'àâäáãåçéèêëîïíìôöóòõùûüúÿý', 'aaaaaaceeeeiiiiooooouuuuyy'), '[^[:alnum:]]+', ' ', 'g'))=s.normalized_name
 ORDER BY r.essential DESC,r.id LIMIT 1), s.id) AS reference_id
 FROM seeds s
)
INSERT INTO catalog_product_references(id,name,category,aliases,essential)
SELECT reference_id,name,category,aliases,TRUE FROM resolved
ON CONFLICT (id) DO UPDATE SET essential=TRUE,
 aliases=concat_ws('|',NULLIF(catalog_product_references.aliases,''),EXCLUDED.aliases);
CREATE INDEX idx_catalog_references_essential ON catalog_product_references(essential,category,name);
