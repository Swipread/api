# Recommendation

The API asks the recommendation service for articles and streams events to it. The full interface is the backend / recommender contract (v1.4); this page summarises what it means for this codebase. Code: `swipread.api.reco`.

## Asking for recommendations

`RecommendationClient.recommend(request)` is called by `FeedRefiller` when the stock drops under the threshold or after an invalidation.

```http
POST /v1/recommendations
{
  "userId": "u_42",
  "count": 37,
  "exclude": ["a_101", "a_102"],
  "context": { "sessionId": "s_8f3a", "locale": "fr-FR", "device": "mobile" }
}
```

- `count` = stock size (50) − current stock, so the stock never exceeds 50.
- `exclude` = the current stock. The recommender excludes the reader's history itself; the API additionally filters `seen:{userId}` (see [Feed](feed.md#refilling-the-stock)).
- `context.sessionId` and `context.device` come from the optional `X-Session-Id` and `X-Device` headers of `GET /v1/feed`; `locale` comes from the reader's profile.

Expected answer: `requestId`, `modelVersion`, `generatedAt` and `items` (`articleId`, `score`, `reason` among `similar`, `collaborative`, `explore`, `trending`, `onboarding`), already sorted.

## Implementations

| Implementation | Profile | Behaviour |
| --- | --- | --- |
| `HttpRecommendationClient` | any except `mock-reco` | `POST {swipread.reco.base-url}/v1/recommendations` with a JDK HTTP client, 300 ms connect and read timeout |
| `MockRecommendationClient` | `mock-reco` | `count` random articles from the database, excluding `exclude` and the reader's history; `score: 0.5`, `reason: "explore"`, `modelVersion: "mock"`, `requestId` `r_` + 12 hex characters |

The client never throws: it returns a `RecommendationResult`, `Success` or `Failure`:

| Recommender answer | Result | API reaction |
| --- | --- | --- |
| 200 with items | `Success` | Items appended to the stock, completed by the fallback if short |
| 200 without body | `Failure(UNAVAILABLE)` | Fallback |
| 400 | `Failure(BAD_REQUEST)` | Error logged with the request (a bug on our side), fallback, no retry |
| 404 | `Failure(UNKNOWN_USER)` | Fallback (a new reader the recommender does not know yet) |
| 5xx, timeout, network error | `Failure(UNAVAILABLE)` | Fallback; retried at the next refill |

## Events stream

Reader events and `user_deleted` messages are published on the Redis stream `events` through the outbox (see [Events](events.md#publication-through-the-outbox)). The recommender reads it with its own consumer group and must be idempotent on `eventId`.

In development, `MockEventsConsumer` (profile `mock-reco`) plays the recommender's part: it reads the stream in the `mock-reco` consumer group, logs each message (`Mock reco received view for u_… (article a_…, event e_…)`) and acknowledges it. The group is created at startup if missing, starting from new messages; an existing group is reused. Several API instances share the group, and each removes its consumer from the group when it stops.

## Fallback lists

The recommender writes `fallback:{slug}` lists in Redis (recent popular articles per topic, 1-hour TTL). The API reads them for the fallback feed and never writes them. Slugs are stable for that reason: renaming a topic changes its label, never its slug.

## Database access

The recommender reads `app` and writes `reco` with a login role that belongs to `swipread_reco` (migration V5): read-only on `app` except the email, password hash and display name of `app.users`; read-write and table creation on `reco`. See [Database](../database.md#recommender-role).

## What the recommender team must know

The contract's revision history lists the changes. In short:

- event type `unsave`; `read` events may come without `scrollDepth`;
- `user_deleted` messages: erase `reco.user_profiles` for that user;
- the stream entry has a single `data` field;
- fallback keys are by slug;
- messages arrive within about 200 ms of the request, and exceptionally twice;
- open question: should a topic change be announced (`topics_changed`), or does the recommender read `app.user_topics` at each call?

## Tests

`HttpRecommendationClientTest` (request format, 400/404/503, real 300 ms timeout, unreachable service, against a local HTTP server), `MockEventsConsumerIntegrationTest` (message read and acknowledged, existing group, consumer removed at shutdown, unreadable message only logged), `RecoRoleIntegrationTest`.
