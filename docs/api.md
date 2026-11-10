# API reference (v1)

Every endpoint the clients use: request, response, validation rules and errors. The formats shared with the recommendation service (recommendations, `events` stream, Redis keys) are in the backend / recommender contract and summarised in [Recommendation](features/recommendation.md).

- Base URL in development: `http://localhost:8080`
- OpenAPI spec: `GET /v3/api-docs`, Swagger UI: `/swagger-ui.html`
- Executable scenarios (Bruno, Postman): [Testing](testing.md#end-to-end-scenarios-bruno-and-postman)
- How the feed and events work internally: [Feed](features/feed.md), [Events](features/events.md)

## Conventions

- JSON in camelCase, ISO 8601 UTC dates, prefixed ids (`u_` user, `a_` article, `r_` recommendation batch, `e_` event, `s_` session).
- Authentication: `Authorization: Bearer <accessToken>` on every endpoint except `register`, `login`, `refresh`, `logout` (which use the refresh cookie instead), the OpenAPI docs and `/actuator/health`.
- The client never sends a `userId`: it is read from the token.
- CORS: allowed origins are configured per environment (`swipread.cors.allowed-origins`, `http://localhost:5173` in development), with credentials allowed. Methods `GET, POST, PUT, PATCH, DELETE`; headers `Authorization, Content-Type, X-Session-Id, X-Device`. Preflight `OPTIONS` requests need no token.
- Responses are `application/json`.
- Errors are RFC 9457 `ProblemDetail` with `Content-Type: application/problem+json`:

```json
{
  "title": "Bad Request",
  "status": 400,
  "detail": "Request validation failed",
  "instance": "/v1/auth/register",
  "errors": [{ "field": "email", "code": "Email", "message": "must be a well-formed email address" }]
}
```

The `ProblemDetail` schema is declared in the OpenAPI spec for every 4xx answer: 400 on any endpoint that takes input, 401 on any authenticated endpoint, 403 on admin routes, and the other codes (404, 409, 422) where they can happen.

`errors` is only present on validation errors. `code` is the violated constraint (`NotBlank`, `NotNull`, `Size`, `Email`, `Pattern`): clients should rely on `field` and `code` to display their own message. `message` is always in English, whatever the `Accept-Language` header or the server's locale. `detail` is in English too.

## Summary

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| POST | `/v1/auth/register` | — | Create an account; returns an access token and sets the refresh cookie |
| POST | `/v1/auth/login` | — | Log in; same response |
| POST | `/v1/auth/refresh` | cookie | New access token (refresh token rotation) |
| POST | `/v1/auth/logout` | cookie | Revoke the refresh token |
| GET | `/v1/me` | bearer | Profile and chosen topics |
| PATCH | `/v1/me` | bearer | Update the profile (`displayName`) |
| DELETE | `/v1/me` | bearer | Delete the account and personal data |
| GET | `/v1/topics` | bearer | Topics offered at onboarding |
| PUT | `/v1/me/topics` | bearer | Replace the chosen topics |
| GET | `/v1/feed` | bearer | Next page of the feed (10 articles) |
| POST | `/v1/events` | bearer | Send a batch of events |
| GET | `/v1/me/saved` | bearer | Saved articles, paginated |
| GET | `/v1/me/history` | bearer | Reading history (opened articles), paginated |
| GET | `/v1/admin/topics` | admin | Topics with article and reader counts |
| POST | `/v1/admin/topics` | admin | Create a topic |
| PATCH | `/v1/admin/topics/{id}` | admin | Rename a topic |
| GET | `/v1/admin/sources` | admin | Ingested sources |
| POST | `/v1/admin/sources` | admin | Add a source (the feed is checked first) |
| PATCH | `/v1/admin/sources/{id}` | admin | Activate or deactivate a source |
| DELETE | `/v1/admin/sources/{id}` | admin | Delete a source that has no articles |

## Auth

### POST /v1/auth/register

```json
{ "email": "alice@example.org", "password": "password123", "displayName": "Alice", "locale": "fr-FR" }
```

| Field | Rule |
| --- | --- |
| email | Required, valid email, ≤ 254 characters, stored in lowercase |
| password | Required, 8 to 72 characters |
| displayName | Required, ≤ 50 characters |
| locale | Optional, `xx-XX` form, default `fr-FR` |

Response **201**:

```json
{ "accessToken": "eyJhbGciOiJIUzI1NiJ9...", "tokenType": "Bearer", "expiresIn": 900, "userId": "u_01M4T6FKYMAGRVXAG4VNE8DQ5W" }
```

`expiresIn` is the access token lifetime **in seconds** (15 minutes). The response also sets the refresh token cookie:

```text
Set-Cookie: swipread_refresh=…; Path=/v1/auth; Max-Age=2592000; HttpOnly; SameSite=Strict; Secure
```

`Secure` is absent in `dev` and `test` (plain HTTP). The cookie is never readable from JavaScript; the browser sends it to `/v1/auth` routes only, provided the client calls them with `credentials: "include"`.

Errors: 400 validation, 409 email already registered.

### POST /v1/auth/login

```json
{ "email": "alice@example.org", "password": "password123" }
```

Response **200**: same as `register`, cookie included. Errors: 400 validation, 401 invalid credentials (same answer, and the same response time, whether the email exists or not).

The access token is an HS256 JWT (`sub` = user id, `roles` claim), valid for 15 minutes. It is renewed with the refresh token, without asking for the password again.

### POST /v1/auth/refresh

No body and no `Authorization` header: only the `swipread_refresh` cookie (`credentials: "include"`). Response **200**, same format as `login`:

- **Rotation.** The cookie is replaced with a new refresh token, valid for 30 days again (sliding lifetime). The previous one stops working.
- **Concurrent tabs.** A token replaced less than 10 seconds ago still gets an access token, without a new cookie (the browser already has the new one).
- **Reuse.** A replaced token presented later is treated as stolen: the whole session of that device is revoked, including the newest token.
- The role is read from the database: a promotion to `ADMIN` takes effect at the next renewal.

Error **401** `"Invalid refresh token"` (missing, unknown, expired, revoked or reused cookie), with the cookie cleared: the client must send the reader to the login screen.

### POST /v1/auth/logout

With the cookie, without `Authorization`. Revokes the device's session (the refresh token's family) and clears the cookie. Response **204**, even without a cookie. The current access token stays valid until it expires: the client must forget it.

## Profile and onboarding

### GET /v1/me

Response **200**:

```json
{
  "id": "u_01M4T6FKYMAGRVXAG4VNE8DQ5W",
  "email": "alice@example.org",
  "displayName": "Alice",
  "locale": "fr-FR",
  "createdAt": "2026-10-13T08:36:21.512Z",
  "role": "READER",
  "topics": [{ "id": 1, "slug": "tech", "label": "Tech" }]
}
```

`role` is `READER` or `ADMIN`. Only `ADMIN` can call `/v1/admin/**`; other accounts get **403**. The role is also carried by the access token, so a change takes effect at the next token renewal (15 minutes at most).

### PATCH /v1/me

```json
{ "displayName": "Alice M." }
```

`displayName`: required, not blank, ≤ 50 characters, trimmed. Response **200**: the full profile, as `GET /v1/me`. Error 400 if `displayName` is invalid.

### DELETE /v1/me

Response **204**, no body. Deleted:

- the account, its topics and its per-article state (saved, likes, history);
- all its events in `app.events`;
- its refresh tokens;
- its Redis keys (`feed:{userId}*`, `seen:{userId}`).

The recommendation service is told through a `user_deleted` message on the `events` stream and erases its own data. The account's access token is refused afterwards (**401**): the client must forget it and go back to the login screen. The email can be used again to sign up.

### GET /v1/topics

Response **200**, sorted by label:

```json
[{ "id": 4, "slug": "economy", "label": "Économie" }, { "id": 1, "slug": "tech", "label": "Tech" }]
```

### PUT /v1/me/topics

Replaces the reader's topics.

```json
{ "topicIds": [1, 2, 3] }
```

`topicIds`: 3 to 50 ids. Response **200**: the saved topics (same format as `GET /v1/topics`). Error 400 if an id is unknown (`"detail": "Unknown topic ids: [9999]"`) or if there are fewer than 3 ids (`errors[0].field` is `topicIds`).

After saving, the feed stock, computed from the previous topics, is emptied. The next `GET /v1/feed` serves its first page from the fallback feed on the new topics, then the recommender takes over in the background. Articles already served stay excluded. A rejected request (400) empties nothing.

## Feed

### GET /v1/feed

| Optional header | Purpose |
| --- | --- |
| `X-Session-Id` | Current session, forwarded to the recommender (`context.sessionId`) |
| `X-Device` | `mobile` or `desktop`, forwarded to the recommender |

Response **200**:

```json
{
  "items": [
    {
      "articleId": "a_387",
      "title": "…",
      "summary": "…",
      "url": "https://…",
      "imageUrl": "https://…",
      "author": "…",
      "sourceName": "Le Journal Tech",
      "language": "fr",
      "wordCount": 850,
      "publishedAt": "2026-10-12T08:00:00Z",
      "topics": [{ "id": 1, "slug": "tech", "label": "Tech" }],
      "liked": false,
      "saved": false,
      "requestId": "r_7c21",
      "reason": "similar",
      "position": 0
    }
  ]
}
```

- Up to 10 articles per call; each call consumes them (there is no going back).
- `requestId` and `reason` must be copied into the article's events. For an article served by the fallback feed, both are `fallback`.
- `position` is the index in the page, from 0.
- `topics`: topics detected for the article, most confident first; empty when the ingestion found none.
- `liked` and `saved`: the current reader's state for the article, always present.
- `null` fields are omitted (`imageUrl`, `author` in particular).
- A new reader's first call is served by the fallback feed; the next ones come from the recommender once the stock is filled in the background.

## Events

### POST /v1/events

Clients send events every 5 seconds, as soon as 20 are queued, and when the app closes.

```json
{
  "sessionId": "s_8f3a",
  "events": [
    {
      "eventId": "e_01J9X",
      "type": "view",
      "articleId": "a_387",
      "requestId": "r_7c21",
      "position": 3,
      "occurredAt": "2026-10-12T10:41:12Z",
      "payload": { "dwellMs": 4200 }
    }
  ]
}
```

| Field | Rule |
| --- | --- |
| sessionId | Required, ≤ 64 characters |
| events | 1 to 100 events |
| eventId | Required, ≤ 64 characters, generated by the client (UUID or ULID), used for deduplication |
| type | `impression`, `view`, `open`, `read`, `like`, `unlike`, `save`, `unsave`, `share`, `hide` |
| articleId | Required, ≤ 64 characters |
| requestId | Optional, ≤ 64 characters, copied from the feed |
| position | Optional, ≥ 0 |
| occurredAt | Required, at most one day in the future |

| Type | Payload |
| --- | --- |
| view | `dwellMs` required (0 to 86,400,000) |
| read | `readMs` required (0 to 86,400,000), `scrollDepth` optional (0 to 1) |
| share | `channel` required (non-blank string) |
| hide | `reason` optional (string) |
| others | none |

Response **202**:

```json
{ "accepted": 1, "duplicates": 0 }
```

- An `eventId` already received (with the same `occurredAt`) is ignored and counted in `duplicates`: resending a batch after a network error is safe.
- One invalid event rejects the whole batch with 400 (`"detail": "Event e_01J9X (read): scrollDepth must be between 0 and 1"`).
- `like`, `save`, `share`, `hide` and a complete `read` (`scrollDepth > 0.8`, or `readMs` ≥ 60 % of the estimated reading time at 230 words per minute) renew the reader's feed stock, keeping the next 10 articles. Example: for an 850-word article (3 min 42 s estimated), a `read` is strong from `readMs` = 133,044.
- `unsave` removes the article from the saved list, like `unlike` removes a like. Neither renews the stock.

## Saved articles and history

### GET /v1/me/saved

| Parameter | Rule |
| --- | --- |
| limit | 1 to 100, default 20 |
| cursor | Optional, the `nextCursor` of the previous page |

Response **200**, most recently saved first, hidden articles excluded:

```json
{
  "items": [{ "article": { "id": "a_387", "title": "…", "summary": "…", "url": "https://…", "sourceName": "…", "language": "fr", "wordCount": 850, "publishedAt": "2026-10-12T08:00:00Z", "topics": [{ "id": 1, "slug": "tech", "label": "Tech" }] }, "savedAt": "2026-10-12T10:41:12Z", "liked": true }],
  "nextCursor": "MjAyNi0xMC0xMlQxMDo0MToxMlp8YV8zODc"
}
```

`article` has the article fields of `GET /v1/feed`, with `id` instead of `articleId`. `liked` tells whether the article is also liked. `nextCursor` is absent on the last page. Error 400 on an invalid cursor. To remove an article, send an `unsave` event: there is no delete endpoint.

### GET /v1/me/history

Same parameters as `GET /v1/me/saved`. Response **200**, most recently opened first:

```json
{
  "items": [{ "article": { "id": "a_387", "title": "…", "topics": [{ "id": 1, "slug": "tech", "label": "Tech" }], "…": "…" }, "readAt": "2026-10-12T10:41:12Z", "liked": false, "saved": true }],
  "nextCursor": "MjAyNi0xMC0xMlQxMDo0MToxMlp8YV8zODc"
}
```

- An article enters the history with an `open` or `read` event. Cards that were only shown or scrolled past (`impression`, `view`) are not in it.
- `readAt` is the **latest** opening: a reopened article moves back to the top, without duplicates.
- Hidden articles are excluded.
- Grouping by day is left to the client.

## Administration

`ADMIN` role only (the `role` field of `GET /v1/me`). Readers get **403**.

### GET /v1/admin/topics

Response **200**, sorted by label:

```json
[{ "id": 1, "slug": "tech", "label": "Tech", "articleCount": 128, "readerCount": 42 }]
```

- `articleCount`: articles tagged with the topic by the ingestion.
- `readerCount`: readers who chose the topic.

### POST /v1/admin/topics

```json
{ "label": "Jeux vidéo" }
```

`label`: required, ≤ 50 characters. The `slug` is derived from the label (lowercase, accents removed, non-alphanumerics replaced by dashes): `jeux-video`. Response **201** in the `GET /v1/admin/topics` format, with zero counts. The topic is offered at onboarding right away.

Errors: **409** if a topic already has this slug (`"Jeux Video"` and `"jeux vidéo"` give the same one), **400** if the label is blank or contains no letter or digit.

### PATCH /v1/admin/topics/{id}

```json
{ "label": "Jeux" }
```

Changes the label only: the `slug` keeps its original value, because the recommender uses it in its `fallback:{slug}` keys. Response **200** in the `GET /v1/admin/topics` format. Error **404** if the topic does not exist.

There is no endpoint to delete a topic.

### GET /v1/admin/sources

Response **200**, sorted by name:

```json
[{ "id": 3, "name": "Le Journal Tech", "siteUrl": "https://journaltech.example", "feedUrl": "https://journaltech.example/rss", "language": "fr", "license": "Summary and link", "active": true }]
```

`license` is omitted when empty.

### POST /v1/admin/sources

```json
{ "name": "Le Journal Tech", "siteUrl": "https://journaltech.example", "feedUrl": "https://journaltech.example/rss", "language": "fr", "license": "Summary and link" }
```

| Field | Rule |
| --- | --- |
| name | Required, ≤ 100 characters |
| siteUrl, feedUrl | Required, `http` or `https` URL, ≤ 2048 characters |
| language | Required, lowercase ISO 639-1 code (`fr`, `en`…) |
| license | Optional, ≤ 200 characters |

Before saving, the API downloads `feedUrl` (5 s to connect, 10 s to answer, 5 MB at most, 5 redirects at most) and parses it as RSS or Atom. Response **201** in the `GET /v1/admin/sources` format, `active: true`.

SSRF protection: only public feeds are downloaded, over `http` or `https` on ports 80 and 443. Loopback, private (10/8, 172.16/12, 192.168/16, 100.64/10, fc00::/7), link-local (including cloud metadata at 169.254.169.254) and multicast addresses are refused, including as redirect targets. The check happens when the connection is opened, so a domain that changes address in between (DNS rebinding) is refused too.

Errors:

- **409** if a source already uses this `feedUrl`;
- **422** if the feed is unreachable (`"Feed is unreachable"`), answers an HTTP error (`"Feed answered HTTP 404"`), is not a valid feed (`"Not a valid RSS or Atom feed"`), has no articles (`"Feed has no articles"`), targets a forbidden address (`"Feed address is not allowed"`), redirects more than 5 times (`"Feed redirects too many times"`) or exceeds 5 MB (`"Feed is larger than 5 MB"`). The `detail` can be shown to the administrator as is.

### PATCH /v1/admin/sources/{id}

```json
{ "active": false }
```

`active` is required and is the only editable field. An inactive source is skipped by the ingestion; its published articles stay in feeds. Response **200** in the `GET /v1/admin/sources` format. Error **404** if the source does not exist.

### DELETE /v1/admin/sources/{id}

Response **204**. Error **409** if the source already has articles: deactivate it instead, because deleting its articles would erase readers' saved lists and history. Error **404** if the source does not exist.
