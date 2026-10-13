-- Historique de lecture (écart au contrat) : dernière ouverture de l'article (événement open ou read)
ALTER TABLE app.user_articles ADD COLUMN opened_at timestamptz;

CREATE INDEX user_articles_opened_idx ON app.user_articles (user_id, opened_at DESC)
  WHERE opened_at IS NOT NULL;

-- Reprise de l'existant depuis app.events, source de vérité
UPDATE app.user_articles ua
SET opened_at = e.last_opened_at
FROM (SELECT user_id, article_id, max(occurred_at) AS last_opened_at
      FROM app.events
      WHERE type IN ('open', 'read') AND article_id IS NOT NULL
      GROUP BY user_id, article_id) e
WHERE ua.user_id = e.user_id AND ua.article_id = e.article_id;
