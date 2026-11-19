# Deployment

The API is a Spring Boot application packaged as one executable JAR. It needs PostgreSQL 17 with pgvector, Redis ≥ 6.2, and network access to the recommendation service. Configuration is entirely through environment variables: see the [production checklist](configuration.md#production-checklist).

## Build

```bash
./mvnw -DskipTests package     # target/api-0.0.1-SNAPSHOT.jar
java -jar target/api-0.0.1-SNAPSHOT.jar
```

Run the tests before building a release (`./mvnw test`, Docker required).

The version comes from the `revision` property of `pom.xml` (`0.0.1-SNAPSHOT` by default). A release sets it from the tag, `-Drevision=1.2.0`, so that the JAR, and the startup log (`Starting ApiApplication v1.2.0`), carry the released version.

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

1. **Create the first admin.** Sign up in the web app, then, on the server, `UPDATE app.users SET role = 'ADMIN' WHERE email = '…'` (with Docker Compose: `docker compose exec postgres psql -U swipread`). Log in again: the role is read from the access token, renewed every 15 minutes at most.
2. **Create the topics.** The topic list is empty, and readers cannot finish onboarding until topics exist. The web app lets an admin without topics reach the administration: its onboarding screen shows a « Créer les thèmes » link. `POST /v1/admin/topics` works too. Then pick your own topics.
3. **Add sources** through the administration, with their topics and after checking their reuse conditions; the ingestion reads them within 15 minutes and fills `app.articles` (see [Ingestion](features/ingestion.md)).
4. **Create the recommender's login role** in the `swipread_reco` group (see [Database](database.md#recommender-role)).

## Operations

| Concern | What to know |
| --- | --- |
| Health | `GET /actuator/health` (liveness and readiness groups) for probes |
| Scaling | Stateless: run several instances behind a load balancer. The refill lock is in Redis, the outbox relay uses `SKIP LOCKED`, the scheduled jobs are idempotent |
| Scheduled jobs | Partitions of `app.events` (startup and 03:00 UTC), refresh token purge (03:30 UTC), outbox relay (every 200 ms), ingestion (every 15 minutes, one instance at a time through an advisory lock), on every instance |
| Redis | Holds the feed stocks and the events stream (7 days). Losing it loses prepared stocks (rebuilt on the next page) and unconsumed stream messages; no business data |
| PostgreSQL | Source of truth. Back it up; `app.events` grows with traffic, one partition per month |
| Secrets | `SWIPREAD_JWT_SECRET`, database and Redis passwords. Rotating the JWT secret logs everyone out of their access token; refresh tokens keep working |
| Logs | Standard Spring Boot logging to stdout. Recommender fallbacks are logged at `INFO`/`WARN`, refresh token reuse at `WARN`, one `Ingestion: …` line per pass and each failing source at `INFO` |
| TLS | Terminate HTTPS in front of the API; keep the refresh cookie `Secure` (default) |

## Production

Swipread runs on a single server with Docker Compose: Caddy in front (HTTPS with a Let's Encrypt certificate), the web app on `/`, this API on `/v1` (and `/actuator/health`), PostgreSQL and Redis on the internal network only. The OpenAPI spec and Swagger UI are not exposed there. The server's configuration (Compose file, Caddy, deploy and backup scripts, server setup) lives in the organisation's private `infra` repository; the API itself only reads the environment variables above.

## Releasing

A release is a version tag on a commit of `main`. It deploys to production:

```bash
git switch main && git pull
git tag -a v1.2.0 -m "Swipread API 1.2.0"
git push origin v1.2.0
```

Only admins can create, move or delete `v*` tags (a tag ruleset). A tag with a suffix, such as `v1.2.0-rc.1`, makes a pre-release, deployed the same way.

The [`Release`](../.github/workflows/release.yml) workflow then runs:

| Job | Does |
| --- | --- |
| Check the tag | The tag is a version (`v1.2.0`) and points to a commit of `main` |
| Checks | The [CI](testing.md#continuous-integration) workflow, reused as is |
| Build and publish the image | Builds the [container image](#container-image) for `linux/amd64` with `VERSION` set from the tag, and pushes `ghcr.io/swipread/api:<version>` (public, like the code) |
| Deploy to production | Connects to the server over SSH. The key can only ask for `deploy api <version>`: the server dumps the database, pulls the image, starts it and waits for `/actuator/health` to be `UP`; if it is not within 2 minutes, it restores the previous version and the job fails |
| Create the GitHub release | With notes generated by GitHub (merged pull requests, link to the full changelog), only once the deployment is healthy |

One release runs at a time. When a job fails, there is no GitHub release and the previous version keeps running: fix the cause, then *Re-run failed jobs* (the image is rebuilt from the same commit) or push a new tag. Rolling back to an earlier version is done on the server (see the `infra` repository).

**Migrations only go forward.** Flyway applies them when the new version starts. A failed migration is rolled back by PostgreSQL and the previous version comes back. A successful migration stays applied even if the new version is then rolled back, so the previous version runs on the new schema: that is safe only because migrations are additive. To undo one, restore the dump taken just before the deployment.

### GitHub settings

| Where | Name | Value |
| --- | --- | --- |
| Repository variable | `APP_ORIGIN` | Public origin of the app, the environment's link |
| Environment `production` (deployments from `v*` tags only), variable | `DEPLOY_HOST` | The server's hostname |
| Environment `production`, secret | `DEPLOY_SSH_KEY` | Private half of the release key pair, restricted on the server to the deploy command |
| Environment `production`, secret | `DEPLOY_KNOWN_HOSTS` | The server's host key, checked against the fingerprint read on the server: a release never trusts an unknown host |

Because the environment only accepts `v*` tags, a workflow started from a branch or a pull request cannot read the deploy key.

### Learnt from the first release

- A new package on GHCR is created **private**: the first deployment of a new image fails with `unauthorized` until an organisation owner makes it public (the package's settings → *Change visibility*). GitHub offers no API for this.
- The image build downloads Maven through `mvnw`. The build image has no `unzip`, so the wrapper fetches the `.tar.gz` distribution, whereas CI fetches the `.zip`. Maven Central once answered 404 for it; re-running the job fixed it.

## Release checklist

1. CI is green on the commit to tag.
2. New migrations are additive (a committed migration is never edited).
3. OpenAPI changes are announced to the web team (they regenerate their types, and release after the API when they depend on it), contract changes to the recommendation team.
4. Production variables are set as in the [checklist](configuration.md#production-checklist), with `SWIPREAD_SOURCES_ALLOW_LOCAL_FEEDS` unset.
5. Tag, push the tag and follow the `Release` workflow until the GitHub release exists.
6. After deployment: health is `UP`, the log says `Starting ApiApplication v<version>`, log in from the web app, scroll the feed, and check the logs for `Using fallback feed` (expected only when the recommender is unavailable).
