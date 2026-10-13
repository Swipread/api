-- Rôle applicatif (écart au contrat : colonne absente du DDL d'origine)
ALTER TABLE app.users
  ADD COLUMN role text NOT NULL DEFAULT 'READER'
  CONSTRAINT users_role_check CHECK (role IN ('READER', 'ADMIN'));
