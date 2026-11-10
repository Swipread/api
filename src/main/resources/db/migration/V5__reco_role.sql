-- Rôle de groupe du service de reco (contrat : lecture seule sur app, écriture sur reco).
-- Sans connexion : l'exploitation crée l'utilisateur qui se connecte et le rattache à ce rôle
-- (CREATE ROLE reco_service LOGIN PASSWORD '…' IN ROLE swipread_reco), voir docs/configuration.md.
-- Un rôle est global au serveur PostgreSQL : si l'utilisateur de migration n'a pas le droit CREATEROLE,
-- un administrateur doit créer swipread_reco avant de lancer cette migration.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'swipread_reco') THEN
    IF NOT (SELECT rolcreaterole OR rolsuper FROM pg_roles WHERE rolname = current_user) THEN
      RAISE EXCEPTION 'Role swipread_reco is missing and % cannot create it: run "CREATE ROLE swipread_reco NOLOGIN" as an administrator first', current_user;
    END IF;
    CREATE ROLE swipread_reco NOLOGIN;
  END IF;
END
$$;

-- app : lecture seule
GRANT USAGE ON SCHEMA app TO swipread_reco;
GRANT SELECT ON ALL TABLES IN SCHEMA app TO swipread_reco;
-- Tables créées plus tard par le backend (migrations, partitions mensuelles de app.events)
ALTER DEFAULT PRIVILEGES IN SCHEMA app GRANT SELECT ON TABLES TO swipread_reco;

-- app.users : pas d'email ni de hash de mot de passe, seulement ce qui sert à recommander
REVOKE SELECT ON app.users FROM swipread_reco;
GRANT SELECT (id, locale, created_at) ON app.users TO swipread_reco;

-- reco : données propres au service, qui peut aussi y créer ses tables
GRANT USAGE, CREATE ON SCHEMA reco TO swipread_reco;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA reco TO swipread_reco;
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA reco TO swipread_reco;
ALTER DEFAULT PRIVILEGES IN SCHEMA reco GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO swipread_reco;
ALTER DEFAULT PRIVILEGES IN SCHEMA reco GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO swipread_reco;
