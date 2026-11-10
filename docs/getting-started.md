# Getting started

## Requirements

- **JDK 25.** Maven comes with the wrapper (`./mvnw`); no need to install it.
- **Docker** running. It provides PostgreSQL and Redis in development and the containers of the integration tests.
- Optional:
  - IntelliJ IDEA (a shared run configuration is included);
  - [Bruno](https://www.usebruno.com/) or Postman for the end-to-end scenarios;
  - Node.js to run those scenarios from the command line.

## Run the API locally

The **`dev`** profile is required. It:

- starts PostgreSQL (`pgvector/pgvector:pg17`) and Redis (`redis:7.4`) through `compose.yaml`;
- provides a development JWT signing key;
- loads sample data: 8 topics, 300 articles and an admin account;
- enables the fake recommendation service (`mock-reco` profile);
- allows browser calls from the web app's dev server, `http://localhost:5173`.

**Command line**

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

**IntelliJ IDEA:** pick the shared **ApiApplication (dev)** run configuration (stored in `.run/`). With any other configuration, set `dev` in *Active profiles*.

Once started:

| URL | Purpose |
| --- | --- |
| <http://localhost:8080/swagger-ui.html> | Swagger UI, to try the endpoints by hand |
| <http://localhost:8080/v3/api-docs> | OpenAPI spec (JSON), used by the web app to generate its types |
| <http://localhost:8080/actuator/health> | Health check |

To call a protected endpoint from Swagger UI:

1. Call `POST /v1/auth/register` (or `login`).
2. Paste the `accessToken` in the *Authorize* dialog.

## A first session with curl

```bash
H=http://localhost:8080
TOKEN=$(curl -s -X POST $H/v1/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"me@example.org","password":"password123","displayName":"Me"}' | jq -r .accessToken)

curl -s $H/v1/topics -H "Authorization: Bearer $TOKEN"
curl -s -X PUT $H/v1/me/topics -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"topicIds":[1,2,3]}'
curl -s $H/v1/feed -H "Authorization: Bearer $TOKEN" | jq '.items[] | {articleId, reason, requestId}'
```

The first feed call returns `reason: "fallback"` articles. The next ones come from the mock recommender, with `reason: "explore"`. [Feed](features/feed.md) explains why.

The access token lasts 15 minutes. To renew it, call `POST /v1/auth/refresh` with the `swipread_refresh` cookie set by `register` or `login` (with curl: `-c cookies.txt` on login, `-b cookies.txt` on refresh).

## Profiles

| Profile | Purpose |
| --- | --- |
| `dev` | Local development: Docker Compose, dev JWT key, sample data, admin account, mock recommender, CORS for `http://localhost:5173`, non-`Secure` refresh cookie |
| `test` | Automated tests: Testcontainers, test JWT key, topics and admin account only, mock recommender, local feeds allowed for the source check |
| `mock-reco` | Replaces the HTTP recommender with random picks from the database and logs the messages of the `events` stream (`Mock reco received …`). Included by `dev` and `test` |
| *(none)* | Production: everything comes from environment variables, see [Configuration](configuration.md) |

Admin account of the `dev` and `test` profiles: `admin@swipread.dev` / `password123`.

Without a profile the application refuses to start until a database, Redis and `SWIPREAD_JWT_SECRET` are configured. This is deliberate: it never runs with development settings by mistake.

## Inspect the database and Redis

Docker assigns random host ports. The simplest is to go through `docker compose`:

```bash
docker compose ps                                          # see the ports
docker compose exec postgres psql -U swipread -d swipread  # SQL console
docker compose exec redis redis-cli                        # Redis console
```

Useful queries:

```sql
SELECT id, email, role, created_at FROM app.users ORDER BY created_at DESC LIMIT 5;
SELECT type, count(*) FROM app.events GROUP BY type;
SELECT * FROM app.user_articles WHERE user_id = 'u_...';
```

```text
LLEN feed:u_...                 # stock size
LRANGE feed:u_... 0 9           # next 10 articles
HGETALL feed:u_...:meta         # origin (requestId|reason) of each article in stock
SCARD seen:u_...                # number of articles already served
XREVRANGE events + - COUNT 5    # latest messages published for the recommender
XINFO GROUPS events             # consumer groups (mock-reco in dev)
```

## Stop and start from scratch

The containers keep running after the application stops (`lifecycle-management: start-only`), which makes restarts faster.

```bash
docker compose down        # stops the containers; data is lost (no volume)
```

## Run the tests

```bash
./mvnw test
```

Docker must be running: integration tests start their own PostgreSQL and Redis containers. See [Testing](testing.md), which also covers the Bruno and Postman scenarios.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| `Failed to configure a DataSource: 'url' attribute is not specified` | Started without the `dev` profile | Enable the `dev` profile |
| Same error with the `dev` profile | Containers created by an older `compose.yaml`, without the `org.springframework.boot.service-connection` label | `docker compose up -d` to recreate them, then restart |
| `Cannot connect to the Docker daemon` | Docker is not running | Start Docker |
| `Port 8080 was already in use` | Another instance is running | Stop it, or start with `-Dspring-boot.run.arguments=--server.port=8089` |
| `Could not resolve placeholder 'SWIPREAD_JWT_SECRET'` or `Binding validation errors on swipread.jwt` | JWT secret missing or shorter than 32 characters | Use the `dev` profile, or set `SWIPREAD_JWT_SECRET` |
| The feed only returns `fallback` articles | The stock has not been filled yet, or the real recommender is unreachable | Call the feed again after a second; look for `Using fallback feed` in the logs |
| The web app gets CORS errors | It is not served from `http://localhost:5173`, or the API is not in the `dev` profile | See `swipread.cors.allowed-origins` in [Configuration](configuration.md) |
