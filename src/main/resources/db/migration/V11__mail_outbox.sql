-- Mails à envoyer : écrits dans la transaction qui les produit (inscription, changement de mot de passe…), puis
-- envoyés en SMTP par MailRelay, qui supprime chaque ligne envoyée. Un échec est retenté plus tard ; après la
-- dernière tentative, la ligne est marquée failed_at et effacée au bout de 7 jours.
CREATE TABLE app.mail_outbox (
  id              bigserial PRIMARY KEY,
  recipient       text NOT NULL,
  subject         text NOT NULL,
  text_body       text NOT NULL,
  html_body       text NOT NULL,
  created_at      timestamptz NOT NULL DEFAULT now(),
  attempts        int NOT NULL DEFAULT 0,
  next_attempt_at timestamptz NOT NULL DEFAULT now(),
  last_error      text,
  failed_at       timestamptz
);
CREATE INDEX mail_outbox_due_idx ON app.mail_outbox (next_attempt_at) WHERE failed_at IS NULL;

-- Adresses et liens personnels : la reco n'y a pas accès (droits par défaut posés par V5)
REVOKE ALL ON app.mail_outbox FROM swipread_reco;
