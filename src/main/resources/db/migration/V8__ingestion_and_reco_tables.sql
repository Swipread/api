-- Contrat v1.5 : ingestion des sources et tables de la reco.

-- Thèmes de chaque source, hérités par ses articles. position = ordre choisi par l'admin :
-- l'article reçoit confidence 1.0, 0.9, 0.8 dans cet ordre, le premier est donc son thème principal.
CREATE TABLE app.source_topics (
  source_id int      NOT NULL REFERENCES app.sources(id) ON DELETE CASCADE,
  topic_id  int      NOT NULL REFERENCES app.topics(id),
  position  smallint NOT NULL CHECK (position BETWEEN 1 AND 3),
  PRIMARY KEY (source_id, topic_id),
  UNIQUE (source_id, position)
);

-- Lecture conditionnelle du flux et état de la dernière lecture
ALTER TABLE app.sources
  ADD COLUMN etag            text,
  ADD COLUMN last_modified   text,
  ADD COLUMN last_fetched_at timestamptz,
  ADD COLUMN last_error      text;

-- Le schéma reco appartient à la reco, qui crée ses tables : celles de V1, jamais utilisées et d'un
-- format qui n'est pas celui de son profil, sont supprimées.
DROP TABLE reco.user_profiles;
DROP TABLE reco.model_runs;
