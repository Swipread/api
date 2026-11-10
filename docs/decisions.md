# Decisions

The backend / recommender contract is the reference. This page records the architecture decisions of this codebase, what had to be decided where the contract was silent or ambiguous, and what is still open.

## Architecture decision records

### ADR-1 · Organise by feature

Packages follow features (`auth`, `feed`, `event`, `source`…), not layers. A feature owns its controller, services, repositories and DTOs. When two features would depend on each other, one notifies the other with a Spring event (`UserTopicsChanged`) instead of calling it.

### ADR-2 · Serve the feed from a Redis stock

The contract requires that readers never wait for the recommender. Each reader has a stock of up to 50 prepared articles in Redis; a page pops 10, and a background refill asks the recommender for more under 20. An empty stock is served synchronously from the fallback feed. Cost: Redis state per reader, and stale stock after a change of taste, handled by invalidation and versioning (ADR-4).

### ADR-3 · Every recommender failure ends in the fallback feed

The recommender client never throws: it returns `Success` or `Failure`, and any failure (timeout, 4xx, 5xx, empty body) completes the stock with the fallback feed (topic lists, then recent articles). The 300 ms timeout bounds how long a refill holds its lock.

### ADR-4 · Version the stock to drop stale refills

A refill can take up to 300 ms in the recommender while a strong event or a topic change invalidates the stock. `feed:{userId}:ver` is incremented before any invalidation, and a Lua script appends a batch only if the version is unchanged since the refill started; otherwise the refill recomputes once. (Contract v1.2.)

### ADR-5 · Publish events through a transactional outbox

Writing to the database and to Redis in the same request cannot be atomic. Stream messages go to `app.event_outbox` in the transaction that stores the events, and a relay publishes them every 200 ms with `FOR UPDATE SKIP LOCKED`. A message is never published for a rolled-back transaction and a Redis outage no longer fails event ingestion; in exchange, delivery is delayed by up to ~200 ms and can happen twice. (Contract v1.3.)

### ADR-6 · Short access tokens and rotating refresh tokens in an HttpOnly cookie

Access tokens are stateless JWTs valid for 15 minutes; refresh tokens are opaque, hashed in the database, sent in an HttpOnly `SameSite=Strict` cookie scoped to `/v1/auth`, rotated at each use, with family revocation on reuse and a 10-second grace period for concurrent tabs. JavaScript never sees a long-lived credential, and a stolen access token expires quickly. (Contract v1.4.)

### ADR-7 · Refuse tokens of deleted accounts

A JWT cannot be revoked. An extra validator checks that the account behind each token still exists (a primary-key lookup per request), so a deleted account cannot keep writing events until its token expires.

### ADR-8 · Check feed URLs against SSRF at connection time

The feed check downloads an admin-supplied URL. Scheme and port are checked at each redirect, and Apache HttpClient 5 resolves hosts through `FeedAddressPolicy`, which refuses internal addresses: the address that is checked is the one connected to, which also defeats DNS rebinding.

### ADR-9 · Flyway owns the schema; SQL where it matters

Hibernate only validates entities. Events, per-article state, the outbox and refresh tokens are written with `JdbcTemplate` to use `ON CONFLICT`, row locks and keyset pagination precisely.

### ADR-10 · Errors in English with stable codes

All errors are RFC 9457 `ProblemDetail`. Validation messages are forced to English whatever the request language, and each field error carries the violated constraint (`code`), so clients translate from stable values instead of parsing text.

## Contract deviations (validated)

Validated on 13/10/2026 and 10/11/2026, and reported in the contract (v1.1 to v1.4).

| Topic | Decision | Reason |
| --- | --- | --- |
| Origin of stock articles | New key `feed:{userId}:meta` (Hash `articleId → requestId\|reason`, 6 h). `feed:{userId}` stays a list of plain ids | The stock mixes several recommender batches and fallback articles; `feed:{userId}:req` only keeps the latest `requestId`, which would mis-attribute events |
| Empty stock | The first page is served directly from the fallback feed, then the recommender refill starts in the background | « The reader never waits for the recommender » |
| User roles (web team request) | Column `app.users.role` (`READER` by default, or `ADMIN`), V3; carried in the `roles` claim; protects `/v1/admin/**` | The web app must show and protect administration screens |
| Removing a saved article (web team request) | New event type `unsave`, like `unlike`: `saved_at` back to `NULL`; published like the others; no invalidation | `app.events` stays the source of truth and the recommender gets the signal |
| `read` without `scrollDepth` (web team request) | `scrollDepth` optional; a `read` is strong if `scrollDepth > 0.8` **or** `readMs` ≥ 60 % of the estimated reading time (230 words per minute) | On the web the article opens on the source's site, where scrolling cannot be measured |
| Reading history (web team request) | Column `app.user_articles.opened_at` (V4, backfilled), set by `open` and `read` to the latest date; `GET /v1/me/history` lists opened articles, hidden ones excluded | The contract plans a history screen on `user_articles` but no column told an opened article from a card scrolled past |
| Account deletion (web team request) | `DELETE /v1/me` deletes the account (cascades), its events, refresh tokens and Redis keys, and publishes `user_deleted` on the stream; tokens of deleted accounts are refused | Erasure of personal data; the recommender erases its own `reco.user_profiles` |
| Stock version (contract v1.2) | `feed:{userId}:ver`, see ADR-4 | Stale batches after an invalidation |
| Outbox (contract v1.3) | `app.event_outbox` (V6), see ADR-5 | Atomic publication |
| Refresh tokens (contract v1.4) | `app.refresh_tokens` (V7), see ADR-6 | No daily login, no long-lived token readable by JavaScript |
| Feed check | SSRF protection, see ADR-8 | User-supplied URL fetched by the server |
| Partitions of `app.events` | `app.events_default` (V2) and a nightly job creating the next month's partition | The contract's DDL only creates October 2026: without this, any later event would fail to insert |
| Stream encoding | One `data` field holding the contract's JSON; 7-day retention with `XADD … MINID ~` | Keeps the nested `payload` as specified; a stream has no TTL |

## Interpretations (validated)

Choices made where the contract was silent or ambiguous, all validated as is on 10/11/2026. The rejected alternative is kept for reference.

| # | Question | Decision | Rejected alternative |
| --- | --- | --- | --- |
| 1 | The event format has no `reason` field | Fallback articles get `requestId = "fallback"`, which clients copy | A `payload.reason` or a new field |
| 2 | Stock "of at most 50" but 50 are requested under the threshold | `count = 50 − current stock` | Always request 50 and truncate |
| 3 | Served articles may not be in the database history yet | The API removes `seen:{userId}` articles from the recommender's answer | Leave exclusion to the recommender only |
| 4 | Recent articles are only planned when `fallback:*` is empty | Always complete a short fallback with recent articles | Serve a shorter stock |
| 5 | `unlike` is listed as strong but absent from the invalidation rule | No invalidation on `unlike`, nor on `unsave` | Add both to strong events |
| 6 | Recommender 400: "log, no retry" | Log as an error and still use the fallback, so the stock is not left empty | Add nothing to the stock |
| 7 | `GET /v1/feed` has no `sessionId` or `device` | Optional `X-Session-Id` and `X-Device` headers; `locale` from the profile | Query parameters |
| 8 | `fallback:{topic}`: by slug or by id? | By slug (`fallback:tech`) | By numeric id |
| 9 | Dedup on `eventId` while the primary key is `(event_id, occurred_at)` | Only identical resends are detected | Unique index on `event_id` alone (impossible on a partitioned table) |
| 10 | A batch with one invalid event | The whole batch is rejected with 400 | Accept the valid events and list the rejected ones |
| 11 | Shape of the `GET /v1/feed` response | A flat list: article fields + `requestId`, `reason`, `position` in the page from 0 | — |
| 12 | Reading time | `readMs` is not added to `total_dwell_ms` (only `view.dwellMs` is) | Add both |
| 13 | Renaming a topic: what happens to the slug? | The slug never changes; only the label | Recompute it and break the recommender's `fallback:{slug}` keys |
| 14 | Deleting a topic (not requested) | No endpoint | `DELETE` only when no article or reader uses it |
| 15 | Deleting a source that has articles | 409: deactivate it instead | Delete its articles too, erasing readers' saved lists and history |
| 16 | Editable fields of a source | `active` only | Name and licence; a new `feedUrl` would need a new check |

## To confirm

| # | Question | Current choice | Alternative |
| --- | --- | --- | --- |
| 17 | Recommender database role (V5): the contract only says "read-only on `app`" | No access to `email`, `password_hash` and `display_name` of `app.users` (only `id`, `locale`, `created_at`); the recommender may create its own tables in `reco` | Read every column; or a `reco` schema managed only by the API's migrations |

## Contract gaps

- **Topic changes and the recommender.** `PUT /v1/me/topics` empties the reader's stock, but the contract does not say whether the recommender must be told: no message is published on the `events` stream. To discuss with the recommendation team: read `app.user_topics` at each call, or a new `topics_changed` message.

## Known technical debt

None at the moment: the eight items found during implementation were addressed (ADR-4 to ADR-10). Add new debt here with its risk and a way out.
