-- Vrai quand la page de l'article n'a pas pu être lue (robots.txt, défi anti-robot…) et que le flux ne donne que le
-- titre et le résumé : word_count est alors bien trop petit pour estimer un temps de lecture, et aucune lecture
-- complète ne s'en déduit.
ALTER TABLE app.articles ADD COLUMN word_count_from_summary boolean NOT NULL DEFAULT false;

-- Articles déjà ingérés : l'ingestion n'a pas gardé l'origine du compte. Un compte qui ne dépasse pas les mots du titre
-- et du résumé vient d'eux (une page lue en donne bien plus).
UPDATE app.articles
SET word_count_from_summary = true
WHERE word_count <= cardinality(regexp_split_to_array(btrim(title || ' ' || summary), '\s+'));
