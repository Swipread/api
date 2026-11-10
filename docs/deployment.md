# Deployment

The API is a Spring Boot application packaged as one executable JAR. It needs PostgreSQL 17 with pgvector, Redis ≥ 6.2, and network access to the recommendation service. Configuration is entirely through environment variables: see the [production checklist](configuration.md#production-checklist).

## Build

```bash
./mvnw -DskipTests package     # target/api-0.0.1-SNAPSHOT.jar
java -jar target/api-0.0.1-SNAPSHOT.jar
```

Run the tests before building a release (`./mvnw test`, Docker required).

## Example: container image

The files below were built and run as shown, against a fresh PostgreSQL and Redis, without any Spring profile (production mode). Put them at the repository root; they are not committed, adapt them to your infrastructure.

`Dockerfile`:

```dockerfile
# Build stage
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -q dependency:go-offline
COPY src src
RUN ./mvnw -q -DskipTests package && cp target/api-*.jar app.jar

# Runtime stage
FROM eclipse-temurin:25-jre
WORKDIR /app
RUN groupadd --system swipread && useradd --system --gid swipread swipread
COPY --from=build /app/app.jar app.jar
USER swipread
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
```

`.dockerignore`:

```text
target
.idea
.git
docs
notes
```

- Dependencies are downloaded in their own layer, so changing the code does not download them again.
- The runtime image has a JRE only and runs as a non-root user.
- `-XX:MaxRAMPercentage=75` sizes the heap from the container's memory limit.

Run it:

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

What the check run confirmed: Flyway applies V1 to V7 on an empty database (including the `swipread_reco` role, as the database owner was a superuser), the application starts in a few seconds, `/actuator/health` is `UP`, sign-up sets a `Secure; HttpOnly; SameSite=Strict` refresh cookie, and with the recommender unreachable the feed falls back as designed. In a real deployment the JWT secret must be stable across restarts and instances: generate it once and store it in your secret manager.

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

1. `./mvnw test` passes.
2. New migrations are additive (a committed migration is never edited).
3. The OpenAPI spec changes are announced to the web team (they regenerate their types) and contract changes to the recommendation team.
4. Production variables are set as in the [checklist](configuration.md#production-checklist), with `SWIPREAD_SOURCES_ALLOW_LOCAL_FEEDS` unset.
5. After deployment: health is `UP`, log in from the web app, scroll the feed, and check the logs for `Using fallback feed` (expected only when the recommender is unavailable).
