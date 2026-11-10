# Testing

## Automated tests

```bash
./mvnw test
```

Docker must be running: integration tests start their own `pgvector/pgvector:pg17` and `redis:7.4` containers through Testcontainers. The full suite (161 tests) runs in about 30 seconds once the images are pulled.

### Test classes

| Kind | Class | Covers |
| --- | --- | --- |
| Unit | `FeedServiceTest`, `FeedRefillerTest` | Paging, liked/saved state, refill threshold, lock, empty stock, fallback complement, seen filter, invalidation, batch discarded and recomputed once when the stock version changes |
| Unit | `EventServiceTest`, `EventRulesTest` | Dedup, chronological order, `articleWordCount`, strong events (including the reading-time rule), payload validation, effect on `user_articles` |
| Unit | `HttpRecommendationClientTest` | Contract request format, 400/404/503, real 300 ms timeout, unreachable service (local HTTP server) |
| Integration | `HttpRecommendationClientContextTest` | Production wiring: without `mock-reco`, the application starts with the HTTP recommender client (dedicated context) |
| Unit | `IdGeneratorTest` | ULID format and ordering |
| Unit | `SlugsTest` | Topic slugs: accents, case, punctuation |
| Unit | `RomeFeedProbeTest` | RSS and Atom, empty feed, HTML, HTTP 404, unreachable host, external entities refused, redirects followed and checked at each hop, redirect loop, local feed refused by default, connection through the policy's resolver, rebinding refused at connection time, unknown host |
| Unit | `FeedAddressPolicyTest` | SSRF: internal IPv4 and IPv6 ranges (including embedded IPv4 and NAT64), public addresses, ports 80/443, `http`/`https`, one internal address among several, unknown host vs forbidden address |
| Integration | `AuthIntegrationTest` | Sign-up, login, 401/409/400 as `ProblemDetail`, English validation messages with their `code` despite `Accept-Language: fr` |
| Integration | `RefreshTokenIntegrationTest` | HttpOnly cookie on login, 15-minute access token, rotation, 10-second grace without a new cookie, reuse revoking the family, missing/unknown/expired token, logout, account deletion, CORS with credentials, hashed storage |
| Integration | `OnboardingIntegrationTest` | Topics, replacing choices, three-topic minimum, `/v1/me` |
| Integration | `CorsIntegrationTest`, `RoleIntegrationTest` | Preflight without token, unknown origin refused; `READER`/`ADMIN` roles, 403 on `/v1/admin/**`, promotion effective with the next token |
| Integration | `FeedIntegrationTest` | First page from the fallback, asynchronous refill to 50, Redis keys, no duplicates across pages, stock emptied on topic change (seen kept, nothing emptied on a 400) |
| Integration | `FallbackFeedProviderIntegrationTest` | `seenAmong` (`SMISMEMBER`), recent articles outside `seen` across several batches, `fallback:{slug}` lists outside `seen` and excluded ids |
| Integration | `FeedStoreIntegrationTest` | Stock version: append at the right version, batch discarded after an invalidation or topic change, account deletion erasing the version |
| Integration | `EventIntegrationTest` | Storage, aggregation, stream publication, dedup, invalidation (`LTRIM` to 10, then refill), partitions |
| Integration | `OutboxIntegrationTest` | Committed messages published in order then removed, nothing published for a rolled-back transaction, enqueue outside a transaction refused |
| Integration | `MockEventsConsumerIntegrationTest` | Mock recommender: message read and acknowledged by the `mock-reco` group, startup with an existing group, consumer removed at shutdown, unreadable message only logged |
| Integration | `RecoRoleIntegrationTest` | `swipread_reco` role (V5) through `SET ROLE`: no login, reads `app` but not the email or hash of `app.users`, no writes to `app`, writes and creates tables in `reco`, tables added later to `app` readable |
| Integration | `SavedArticlesIntegrationTest` | Cursor paging, hidden articles excluded, `unsave`, topics sorted by confidence, `liked` |
| Integration | `HistoryIntegrationTest` | Opened articles only, order by latest opening, late events, hidden excluded, cursor |
| Integration | `UserArticleStatesIntegrationTest` | Batched liked/saved lookup |
| Integration | `AccountIntegrationTest` | Rename, account deletion (database, Redis, `user_deleted` message), token refused afterwards, email reusable |
| Integration | `AdminTopicsIntegrationTest` | Creation (slug, 409 on the same slug), article and reader counts, rename keeping the slug, 403 for readers |
| Integration | `AdminSourcesIntegrationTest` | Creation with a checked feed (local HTTP server), 409 on a used feed, 422 on empty or failing feeds, deactivation, deletion (409 when the source has articles), 403 for readers |
| Integration | `OpenApiIntegrationTest` | Public and complete spec, JSON responses, 4xx errors as `ProblemDetail`, `FieldError.code` |

### Writing an integration test

Extend `swipread.api.IntegrationTest`, which provides:

- `mvc` (MockMvc) and `json` (Jackson `JsonMapper`);
- `registerUser()`, returning a user with its token (`user.bearer()`), and `loginAdmin()` for the seeded admin.

All classes share the same configuration, so Spring reuses one application context and one set of containers. Do not add `@MockitoBean` or test-specific properties without a good reason: that creates a new context with new containers. To isolate a collaborator, construct the class under test by hand with the beans you need (see `OutboxIntegrationTest` or `MockEventsConsumerIntegrationTest`).

Data is not cleaned between tests. Each test creates its own user (random email), and `TestArticles.seed(jdbc, n)` inserts articles idempotently. Wait for asynchronous work (stock refill, outbox relay, stream consumer) with Awaitility (`await().until(...)`), never with `Thread.sleep`.

`TestApiApplication` starts the application with the test containers and without Docker Compose, which is handy for a quick try from the IDE.

## End-to-end scenarios (Bruno and Postman)

The same scenario exists in two versions, for testers on either tool. It walks the whole API with assertions: 47 requests and 52 tests. Test names are in French (the testers' language).

It expects the API in the `dev` profile with the mock recommender: feed tests expect `reason: "explore"`. Each run creates a new user (`smoke+<timestamp>@example.org`), so it can be run again at will. « 08 Account » deletes that user; « 09 Admin » logs in with the seeded admin and creates a « Thème de test » topic once in the development database. No source is created (that would need a real online feed); source creation is covered by the integration tests.

| Folder | Checks |
| --- | --- |
| 01 Auth | Sign-up, email taken (409), invalid body (400 with per-field `code` and English messages despite `Accept-Language: fr`), login, wrong password (401), HttpOnly refresh cookie, rotation, logout, refresh refused after logout (401) |
| 02 Onboarding | Topic list (at least six), choosing three topics, unknown topic (400), `/v1/me` |
| 03 Feed | First page from the fallback, recommender page 1.5 s later without articles already served, topic change then a page from the fallback (stock emptied) |
| 04 Events | `view` + `save` batch accepted, same batch resent and deduplicated, invalid payload, unknown type, `read` without `scrollDepth` |
| 05 Saved | The saved article is listed, invalid cursor (400), `unsave`, list empty afterwards |
| 06 Security and docs | 401 without token and with an invalid token, OpenAPI spec, CORS preflight from the web app's origin, admin route as a reader (403), admin login, admin profile with role `ADMIN` |
| 07 History | The opened article is in the history |
| 08 Account | Rename, account deletion, token refused afterwards (401) |
| 09 Admin | Topics with counts, topic creation (201, or 409 on later runs), duplicate (409), rename, source list, known feed (409), local address refused by the SSRF protection (422), deactivate, reactivate, delete a source with articles (409) |

> Any change to the scenario must be made in both versions.

The refresh cookie is captured from `Set-Cookie` and sent back explicitly in a `Cookie` header, so the scenario does not depend on each tool's cookie jar.

### Bruno: [`bruno/`](bruno/)

- In the Bruno app: *Open Collection* → `docs/bruno`, pick the `dev` environment, then *Run* the collection.
- From the command line (`npm i -g @usebruno/cli`):

```bash
cd docs/bruno
bru run --env dev
# another port: bru run --env dev --env-var baseUrl=http://localhost:8089
```

### Postman: [`postman/`](postman/)

- In Postman: *Import* `swipread.postman_collection.json` and `swipread-dev.postman_environment.json`, select the *Swipread dev* environment, then *Run collection*, in order.
- From the command line with Newman:

```bash
npx newman run docs/postman/swipread.postman_collection.json \
  -e docs/postman/swipread-dev.postman_environment.json
# another port: add --env-var baseUrl=http://localhost:8089
```
