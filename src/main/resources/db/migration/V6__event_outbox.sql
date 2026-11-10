-- Outbox du stream events : les messages sont écrits dans la même transaction que les événements (ou que la
-- suppression d'un compte), puis publiés sur Redis par OutboxRelay, qui supprime les lignes publiées.
CREATE TABLE app.event_outbox (
  id         bigserial PRIMARY KEY,
  message    text NOT NULL,                   -- JSON du message, tel que publié dans le champ data du stream
  created_at timestamptz NOT NULL DEFAULT now()
);

-- Table interne au backend : la reco lit le stream, pas l'outbox (droits par défaut posés par V5)
REVOKE ALL ON app.event_outbox FROM swipread_reco;
