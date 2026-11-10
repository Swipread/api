# Configuration

All configuration lives in `src/main/resources/application.yaml`, overridden by `application-dev.yaml` and `application-test.yaml`. Any property can be set from an environment variable following Spring's relaxed binding: `swipread.feed.page-size` becomes `SWIPREAD_FEED_PAGE_SIZE`.

## Application properties

| Property | Default | Environment variable | Purpose |
| --- | --- | --- | --- |
| `swipread.jwt.secret` | none (required) | `SWIPREAD_JWT_SECRET` | HMAC key that signs the JWTs, at least 32 characters |
| `swipread.jwt.ttl` | `15m` | `SWIPREAD_JWT_TTL` | Access token lifetime |
| `swipread.auth.refresh-ttl` | `30d` | `SWIPREAD_AUTH_REFRESH_TTL` | Refresh token lifetime, restarted at each renewal |
| `swipread.auth.refresh-grace` | `10s` | `SWIPREAD_AUTH_REFRESH_GRACE` | How long a just-replaced refresh token is still accepted without rotation (concurrent tabs) |
| `swipread.auth.refresh-cookie-secure` | `true` | `SWIPREAD_AUTH_REFRESH_COOKIE_SECURE` | `Secure` attribute of the refresh cookie. `false` in `dev` and `test` (plain HTTP); **always `true` in production** |
| `swipread.cors.allowed-origins` | empty | `SWIPREAD_CORS_ALLOWED_ORIGINS` | Frontend origins allowed to call the API from a browser, comma-separated. `http://localhost:5173` in `dev` and `test` |
| `swipread.reco.base-url` | `http://localhost:8081` | `SWIPREAD_RECO_URL` | Recommendation service URL (ignored with `mock-reco`) |
| `swipread.reco.timeout` | `300ms` | `SWIPREAD_RECO_TIMEOUT` | Connect and read timeout towards the recommender (set by the contract) |
| `swipread.feed.page-size` | `10` | `SWIPREAD_FEED_PAGE_SIZE` | Articles per page, and articles kept on invalidation |
| `swipread.feed.stock-size` | `50` | `SWIPREAD_FEED_STOCK_SIZE` | Maximum size of a reader's stock in Redis |
| `swipread.feed.refill-threshold` | `20` | `SWIPREAD_FEED_REFILL_THRESHOLD` | A refill starts when fewer articles than this remain |
| `swipread.sources.allow-local-feeds` | `false` | `SWIPREAD_SOURCES_ALLOW_LOCAL_FEEDS` | Lifts the SSRF protection of the feed check (internal addresses, ports other than 80 and 443). `true` in the `test` profile only, for feeds served locally; **never in production** |

`application.yaml` reads `SWIPREAD_RECO_URL` explicitly for `swipread.reco.base-url`.

The feed sizes and the recommender timeout come from the contract with the recommendation team: discuss with them before changing them. The Redis key lifetimes (6 h, 5 s, 30 days, 7 days) are contract constants in `FeedStore` and `EventStreamPublisher`.

## Notable Spring settings

| Property | Value | Why |
| --- | --- | --- |
| `spring.datasource.hikari.connection-init-sql` | `SET TIME ZONE 'UTC'` | Partition bounds of `app.events` are interpreted in the session time zone |
| `spring.jpa.hibernate.ddl-auto` | `validate` | The schema belongs to Flyway |
| `spring.jpa.open-in-view` | `false` | No SQL during JSON serialisation |
| `spring.jackson.default-property-inclusion` | `non_null` | `null` fields are left out of responses and stream messages |
| `spring.threads.virtual.enabled` | `true` | Virtual threads for HTTP requests |
| `spring.docker.compose.enabled` | `false` (`true` in `dev`) | Docker Compose in development only |
| `management.endpoints.web.exposure.include` | `health,info` | Only these Actuator endpoints are exposed |

## Profiles

| Profile | File | Content |
| --- | --- | --- |
| `dev` | `application-dev.yaml` | Docker Compose (`start-only`), dev JWT key, CORS for the web dev server, non-`Secure` cookie, migrations + `db/seed` + `db/dev-data` |
| `test` | `application-test.yaml` | Test JWT key, CORS for the web dev server, non-`Secure` cookie, local feeds allowed, migrations + `db/seed` |
| `mock-reco` | none | Enables `MockRecommendationClient` and `MockEventsConsumer`; included by `dev` and `test` |

The development and test JWT keys are committed on purpose: they are only used by these profiles and protect nothing.

## Production checklist

Start without any profile and provide at least:

| Variable | Example |
| --- | --- |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://db:5432/swipread` |
| `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` | Owner of the `app` and `reco` schemas (Flyway creates the `vector` extension and the schemas) |
| `SPRING_DATA_REDIS_HOST`, `SPRING_DATA_REDIS_PORT` (+ `SPRING_DATA_REDIS_PASSWORD`) | Redis ≥ 6.2 (`LPOP` with a count, `XADD … MINID`, `SMISMEMBER`) |
| `SWIPREAD_JWT_SECRET` | 32 random characters or more, e.g. `openssl rand -base64 48` |
| `SWIPREAD_RECO_URL` | Internal URL of the recommendation service |
| `SWIPREAD_CORS_ALLOWED_ORIGINS` | The web app's origin, e.g. `https://app.example.com`. CORS allows credentials (the refresh cookie), so origins must be explicit, never `*` |

Also check:

- **Same site.** The web app and the API must share a registrable domain (`app.example.com` and `api.example.com`): the refresh cookie is `SameSite=Strict` and is not sent across sites.
- **HTTPS.** Keep `SWIPREAD_AUTH_REFRESH_COOKIE_SECURE=true` (the default) and terminate TLS in front of the API.
- **First administrator.** `admin@swipread.dev` only exists in `dev` and `test`. In production, promote an existing account: `UPDATE app.users SET role = 'ADMIN' WHERE email = '…'`. It takes effect at the account's next token renewal (15 minutes at most).
- **Swagger.** Swagger UI and `/v3/api-docs` are public by default. Turn them off if needed with `SPRINGDOC_API_DOCS_ENABLED=false` and `SPRINGDOC_SWAGGER_UI_ENABLED=false`.
- **Time zone.** The JVM time zone has no effect: dates are handled as `Instant` and SQL sessions run in UTC.
- **Several instances.** Nothing is kept in memory between requests. The refill lock is in Redis, the outbox relay uses `FOR UPDATE SKIP LOCKED`, and the partition job uses `CREATE TABLE IF NOT EXISTS`: all of them are safe on every instance.
- **Recommender database access.** Migration V5 creates the group role `swipread_reco` (read-only on `app` without the sensitive columns of `app.users`, read-write on `reco`, see [Database](database.md#recommender-role)). The migration user needs `CREATEROLE`; otherwise an administrator runs `CREATE ROLE swipread_reco NOLOGIN` first. Then create the login role used by the recommender, with a password that must not be committed:

  ```sql
  CREATE ROLE reco_service LOGIN PASSWORD '…' IN ROLE swipread_reco;
  ```

A tested container image example is in [Deployment](deployment.md).
