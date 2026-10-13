# Swipread API

Backend d'un réseau social type TikTok où l'on scrolle des articles recommandés. Spring Boot 4.1 / Java 25 / Maven wrapper, package racine `swipread.api`.

## Référence

- `notes/Contrat backend reco.md` fait foi : endpoints, format des événements, clés Redis, feed de repli, DDL.
- Ne jamais changer un format du contrat sans accord explicite. Signaler toute ambiguïté au lieu de trancher seul.
- Écarts au contrat validés, reportés dans le contrat v1.1 puis v1.2 (10/11/2026) :
  - `feed:{userId}:meta` : Hash `articleId → requestId|reason`, TTL 6 h, pour connaître l'origine de chaque article du stock.
  - Partition `app.events_default` + job planifié qui crée la partition du mois suivant.
  - Stream `events` : un seul champ `data` contenant le JSON du contrat, rétention 7 j via `XADD … MINID ~`.
  - Stock vide sur `GET /v1/feed` : repli synchrone pour la 1re page, remplissage reco en arrière-plan.
  - `app.refresh_tokens` (v1.4, V7) : refresh tokens hachés, cookie HttpOnly `swipread_refresh`, rotation et familles.
  - `app.event_outbox` (v1.3, V6) : messages du stream écrits dans la transaction des événements, publiés par `OutboxRelay` toutes les 200 ms.
  - `feed:{userId}:ver` (v1.2) : version du stock incrémentée avant invalidation ou vidage, vérifiée par le script Lua d'ajout d'un lot.
  - Demandes du front (13/10/2026) : colonne `app.users.role` (V3) ; `app.user_articles.opened_at` (V4) pour l'historique ; événement `unsave` ; `scrollDepth` optionnel, `read` fort aussi si `readMs` ≥ 60 % de `wordCount` / 230 mots/min ; message `user_deleted` sur le stream à la suppression d'un compte.

## Architecture

- Organisation par fonctionnalité, pas par couche : `auth`, `user`, `topic`, `article`, `source`, `feed`, `event`, `reco`, plus `common` (erreurs, config transverse) et `security`.
- Routes `/v1/admin/**` réservées au rôle `ADMIN` (claim `roles` du JWT).
- DTO en records Java. Jamais d'entité JPA exposée dans l'API.
- Schéma géré uniquement par Flyway (`src/main/resources/db/migration`), Hibernate en `ddl-auto: validate`. Une migration commitée n'est jamais modifiée : on en ajoute une nouvelle.
- Données de seed : `db/seed` (thèmes et compte `admin@swipread.dev`, profils dev et test), `db/dev-data` (articles d'exemple, profil dev uniquement), en migrations repeatable.
- Connexions JDBC en UTC (`connection-init-sql`) : les bornes de partition du DDL en dépendent.
- La colonne `app.articles.embedding` n'est jamais mappée côté backend.
- Erreurs au format ProblemDetail (RFC 9457) via le `@RestControllerAdvice` de `common`.
- Le `userId` vient toujours du JWT (`sub`), jamais du corps de la requête.
- JWT HS256 signés par l'API (secret en config), validés par le Resource Server. Mots de passe en BCrypt.
- La reco est derrière l'interface `RecommendationClient` : implémentation HTTP (timeout 300 ms) et mock (profil `mock-reco`). Toute erreur de reco aboutit au feed de repli.

## Conventions

- JSON en camelCase, dates ISO 8601 UTC (`Instant`), IDs préfixés (`u_` utilisateur, `a_` article), routes sous `/v1`.
- Code et noms en anglais. Commentaires en français, uniquement s'ils apportent quelque chose.
- Chaque endpoint est documenté pour springdoc (OpenAPI).

## Tests

- Unitaires (JUnit 5 + Mockito) sur la logique de feed et d'événements.
- Intégration avec Testcontainers (`pgvector/pgvector`, `redis`) sur les endpoints principaux. Docker requis.
- `./mvnw test` doit passer à la fin de chaque étape.

## Commandes

- Tests : `./mvnw test`
- Lancer en dev (Docker Compose + mock de reco) : `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`, ou la configuration IntelliJ partagée `ApiApplication (dev)` (`.run/`). Sans profil, l'appli ne démarre pas : pas de datasource ni de secret JWT.
- Profils : `dev`, `test`, `mock-reco` (`dev` et `test` incluent `mock-reco`).
- Le label `org.springframework.boot.service-connection: postgres` de `compose.yaml` est indispensable : Boot ne reconnaît pas l'image pgvector seule.

## Méthode de travail

- Proposer un plan et attendre la validation avant de coder.
- Implémenter étape par étape, tests lancés à la fin de chaque étape.
