# Deployment

The API is a Spring Boot application packaged as one executable JAR. It needs PostgreSQL 17 with pgvector, Redis ≥ 6.2, and network access to the recommendation service. Configuration is entirely through environment variables: see the [production checklist](configuration.md#production-checklist).

## Build

```bash
./mvnw -DskipTests package     # target/api-0.0.1-SNAPSHOT.jar
java -jar target/api-0.0.1-SNAPSHOT.jar
```

Run the tests before building a release (`./mvnw test`, Docker required).

## Container image

The repository ships a production image: [`Dockerfile`](../Dockerfile) and [`.dockerignore`](../.dockerignore). Tests do not run during the image build; CI runs them.

- Dependencies are downloaded in their own layer, so changing the code does not download them again.
- The runtime image has a JRE only and runs as a non-root user (`swipread`). It weighs about 440 MB.
- `-XX:MaxRAMPercentage=75` sizes the heap from the container's memory limit, and `-XX:+ExitOnOutOfMemoryError` makes the JVM exit on an `OutOfMemoryError`, so that the container is restarted instead of running in a broken state.
- The base images are pinned to a major version (`eclipse-temurin:25-jdk`, `25-jre`), so each new image picks up their security fixes.

Build and run it:

```bash
docker build -t swipread-api .
docker run --rm -p 8080:8080 \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/swipread \
  -e SPRING_DATASOURCE_USERNAME=swipread -e SPRING_DATASOURCE_PASSWORD=… \
  -e SPRING_DATA_REDIS_HOST=redis \
  -e SWIPREAD_JWT_SECRET="$(openssl rand -base64 48)" \
  -e SWIPREAD_RECO_URL=http://reco:8081 \
  -e SWIPREAD_CORS_ALLOWED_ORIGINS=https://app.example.com \
  swipread-api
```

What a check run confirmed, against a fresh PostgreSQL and Redis without any Spring profile (production mode), behind an HTTPS reverse proxy: Flyway applies V1 to V7 on an empty database (including the `swipread_reco` role, as the database owner was a superuser), the application starts in a few seconds, `/actuator/health` is `UP`, sign-up from a browser sets a `Secure; HttpOnly; SameSite=Strict` refresh cookie, and with the recommender unreachable the feed falls back as designed. In a real deployment the JWT secret must be stable across restarts and instances: generate it once and store it in your secret manager.

`SWIPREAD_CORS_ALLOWED_ORIGINS` must hold the browser's exact origin, port included, even when the web app and the API share one origin behind a TLS proxy: the API receives plain HTTP from the proxy, so it sees the browser's `https` origin as a cross-origin request and rejects it with 403 when it is not listed.

## First start in production

No data is seeded without the `dev` or `test` profile:

1. **Create the topics.** The topic list is empty; readers cannot finish onboarding until topics exist. Create an admin first (below), then use `POST /v1/admin/topics` or the web app's administration.
2. **Create the first admin.** Sign up normally, then `UPDATE app.users SET role = 'ADMIN' WHERE email = '…'`. The role applies at the next token renewal (15 minutes at most), or right away after logging in again.
3. **Add sources** through the administration; the ingestion fills `app.articles` from active sources.
4. **Create the recommender's login role** in the `swipread_reco` group (see [Database](database.md#recommender-role)).

## Operations

| Concern | What to know |
| --- | --- |
| Health | `GET /actuator/health` (liveness and readiness groups) for probes |
| Scaling | Stateless: run several instances behind a load balancer. The refill lock is in Redis, the outbox relay uses `SKIP LOCKED`, the scheduled jobs are idempotent |
| Scheduled jobs | Partitions of `app.events` (startup and 03:00 UTC), refresh token purge (03:30 UTC), outbox relay (every 200 ms), on every instance |
| Redis | Holds the feed stocks and the events stream (7 days). Losing it loses prepared stocks (rebuilt on the next page) and unconsumed stream messages; no business data |
| PostgreSQL | Source of truth. Back it up; `app.events` grows with traffic, one partition per month |
| Secrets | `SWIPREAD_JWT_SECRET`, database and Redis passwords. Rotating the JWT secret logs everyone out of their access token; refresh tokens keep working |
| Logs | Standard Spring Boot logging to stdout. Recommender fallbacks are logged at `INFO`/`WARN`, refresh token reuse at `WARN` |
| TLS | Terminate HTTPS in front of the API; keep the refresh cookie `Secure` (default) |

## Release checklist

1. `./mvnw test` passes, and the [CI](testing.md#continuous-integration) run of the released commit is green.
2. New migrations are additive (a committed migration is never edited).
3. The OpenAPI spec changes are announced to the web team (they regenerate their types) and contract changes to the recommendation team.
4. Production variables are set as in the [checklist](configuration.md#production-checklist), with `SWIPREAD_SOURCES_ALLOW_LOCAL_FEEDS` unset.
5. After deployment: health is `UP`, log in from the web app, scroll the feed, and check the logs for `Using fallback feed` (expected only when the recommender is unavailable).
