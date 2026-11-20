-- Vérification de l'adresse mail : date de la confirmation, NULL tant que le lecteur n'a pas cliqué le lien.
-- Les comptes existants commencent non vérifiés. La reco n'y a pas accès (droits par colonne de V5).
ALTER TABLE app.users ADD COLUMN email_verified_at timestamptz;

-- Liens à usage unique envoyés par mail (vérification de l'adresse, mot de passe oublié), jamais stockés en clair :
-- seulement leur SHA-256.
CREATE TABLE app.user_tokens (
  id         uuid PRIMARY KEY,
  user_id    text NOT NULL REFERENCES app.users(id) ON DELETE CASCADE,
  purpose    text NOT NULL CONSTRAINT user_tokens_purpose_check CHECK (purpose IN ('verify_email', 'reset_password')),
  token_hash text NOT NULL UNIQUE,              -- SHA-256 en hexadécimal
  created_at timestamptz NOT NULL,
  expires_at timestamptz NOT NULL,
  used_at    timestamptz
);
CREATE INDEX user_tokens_user_idx ON app.user_tokens (user_id, purpose, created_at DESC);

REVOKE ALL ON app.user_tokens FROM swipread_reco;
