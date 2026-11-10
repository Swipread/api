-- Refresh tokens (cookie HttpOnly), jamais stockés en clair : seulement leur SHA-256.
-- Une famille = une connexion sur un appareil. Chaque renouvellement révoque le token présenté et en crée un
-- nouveau dans la même famille (rotation) ; un token remplacé présenté de nouveau révoque toute la famille.
CREATE TABLE app.refresh_tokens (
  id          uuid PRIMARY KEY,
  user_id     text NOT NULL REFERENCES app.users(id) ON DELETE CASCADE,
  family_id   uuid NOT NULL,
  token_hash  text NOT NULL UNIQUE,            -- SHA-256 en hexadécimal
  created_at  timestamptz NOT NULL,
  expires_at  timestamptz NOT NULL,
  revoked_at  timestamptz,
  replaced_by uuid REFERENCES app.refresh_tokens(id) ON DELETE SET NULL
);
CREATE INDEX refresh_tokens_family_idx ON app.refresh_tokens (family_id);
CREATE INDEX refresh_tokens_expires_idx ON app.refresh_tokens (expires_at);

-- Données d'authentification : la reco n'y a pas accès (droits par défaut posés par V5)
REVOKE ALL ON app.refresh_tokens FROM swipread_reco;
