-- La reco calcule et garde ses vecteurs dans reco.article_embeddings : la colonne embedding de app.articles et
-- son index, prévus au départ, n'ont jamais été remplis ni lus.
DROP INDEX app.articles_embedding_idx;
ALTER TABLE app.articles DROP COLUMN embedding;

-- L'API exige un article et un type connu pour chaque événement : la base l'impose aussi.
ALTER TABLE app.events ALTER COLUMN article_id SET NOT NULL;
ALTER TABLE app.events ADD CONSTRAINT events_type_check
  CHECK (type IN ('impression', 'view', 'open', 'read', 'like', 'unlike', 'save', 'unsave', 'share', 'hide'));
