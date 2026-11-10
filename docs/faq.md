# FAQ and troubleshooting

## Running locally

**The application does not start without a profile.**
On purpose: without `dev` (or explicit production variables) there is no database, Redis or JWT secret. Use `-Dspring-boot.run.profiles=dev`.

**Started without a profile against the development database, Flyway fails with "Detected applied migration not resolved locally: 1 topics".**
The development database ran the seed migrations (`db/seed`, `db/dev-data`), which only the `dev` and `test` profiles load. Use the `dev` profile with it, or point a production-mode run at an empty database.

**Port 8080 is already used.**
Another instance is running (often the one in the IDE). Start a second one with `-Dspring-boot.run.arguments=--server.port=8089`; both share the development database and Redis.

**The feed only returns `fallback` articles.**
Normal for a reader's first page: the stock is empty, so the page comes from the fallback feed while the recommender refill runs in the background. Call again after a second. If it persists, the recommender is failing: look for `Using fallback feed` and `Recommendation service` in the logs (in `dev` the mock never fails).

**Where are the messages for the recommender?**
In the Redis stream `events`: `XREVRANGE events + - COUNT 5`. They are published by the outbox relay within about 200 ms of the request. In `dev` the mock consumer logs each one (`Mock reco received …`).

**`POST /v1/events` returned 202 but nothing is on the stream.**
Wait a moment (outbox relay every 200 ms). Duplicate events (`duplicates` in the response) are not republished. Check `SELECT count(*) FROM app.event_outbox` for messages waiting for Redis.

**How do I become admin locally?**
Log in with `admin@swipread.dev` / `password123` (`dev` and `test`). To promote another account: `UPDATE app.users SET role = 'ADMIN' WHERE email = '…'`, then renew the token or log in again.

**Adding a source fails with "Feed address is not allowed".**
The SSRF protection refuses private addresses and ports other than 80 and 443, including `localhost`. Use a public feed. Only the `test` profile allows local feeds.

## Authentication

**The web app logs me out after 15 minutes.**
The refresh request fails. Check that `login` answers with `Set-Cookie: swipread_refresh=…`, that the web app calls `/v1/auth/refresh` with `credentials: "include"` and without an `Authorization` header, that its origin is in `swipread.cors.allowed-origins`, and that both run on the same site (`localhost` and `127.0.0.1` are different sites).

**`/v1/auth/refresh` answers 401 right after a successful one.**
The previous cookie was replayed more than 10 seconds after its rotation, which looks like theft: the whole session was revoked. A client must always keep the latest cookie (browsers do).

**Why is there no `DELETE /v1/me/saved/{id}`?**
Saving and unsaving are events (`save`, `unsave`). The event log stays the source of truth and the recommender receives the signal.

## Tests

**Integration tests fail with "Could not find a valid Docker environment".**
Start Docker: Testcontainers runs PostgreSQL and Redis containers.

**A new integration test makes the suite much slower.**
It probably changed the Spring context (a `@MockitoBean`, a profile or a property), which starts a new context and new containers. Extend `IntegrationTest` as is when possible.

**An assertion on the stream or the stock is flaky.**
The refill, the outbox relay and the mock consumer are asynchronous: wait with Awaitility (`await().until(...)`).

## Recommender

**What does the recommender team need from us?**
The contract and its revision history; [Recommendation](features/recommendation.md) summarises the points that matter, including the open question on topic changes.

**Can the recommender read emails?**
No: its database role only sees `id`, `locale` and `created_at` in `app.users` (decision 17, still to be confirmed with the recommendation team).
