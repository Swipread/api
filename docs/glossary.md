# Glossary

| Term | Meaning |
| --- | --- |
| **Access token** | 15-minute HS256 JWT sent as `Authorization: Bearer …` |
| **Contract** | The interface agreement between this API and the recommendation service, versioned (v1.4) |
| **Dwell time** (`dwellMs`) | Time a card stayed on screen in the feed, sent with `view` |
| **Event** | A reader interaction posted to `/v1/events` (`impression`, `view`, `open`, `read`, `like`, `unlike`, `save`, `unsave`, `share`, `hide`) |
| **Fallback feed** | Articles served without the recommender: `fallback:{slug}` lists, then recent articles; marked `requestId = reason = "fallback"` |
| **Fallback list** | Redis list `fallback:{slug}` of recent popular articles per topic, written by the recommender |
| **Family** | All the refresh tokens descending from one login on one device; revoked together |
| **Feed check** | Download and parsing of a new source's feed before it is saved, with SSRF protection |
| **Grace period** | 10 seconds during which a just-rotated refresh token still works without rotation (concurrent tabs) |
| **Hydration** | Loading article content from the database for ids that came from Redis or the recommender |
| **Invalidation** | Trimming a reader's stock to the next 10 articles and refilling it after a strong event |
| **Outbox** | `app.event_outbox`: stream messages written in the business transaction, published later by `OutboxRelay` |
| **Partition** | Monthly child table of `app.events` (`app.events_YYYY_MM`), plus `app.events_default` |
| **Position** | Index of a card in the feed page (from 0), copied into events |
| **ProblemDetail** | RFC 9457 error body: `type`, `title`, `status`, `detail`, `instance`, and `errors` for validation |
| **Reason** | Why an article was recommended: `similar`, `collaborative`, `explore`, `trending`, `onboarding`, or `fallback` |
| **Recommender** (« la reco ») | The separate service that ranks articles per reader from the events stream and the database |
| **Refill** | Background task asking the recommender for articles when the stock drops under 20 |
| **Refresh token** | Opaque rotating token in the HttpOnly `swipread_refresh` cookie, used to get new access tokens |
| **Request id** (`requestId`) | Id of a recommendation batch (`r_…`), carried by each article and copied into its events |
| **Rotation** | Replacing the refresh token at each use; the old one is revoked |
| **Seen** | Redis set `seen:{userId}` of articles already served (30 days), never served again |
| **Session id** (`sessionId`) | Client-generated id of a browsing session (`s_…`), sent with event batches and as `X-Session-Id` |
| **Slug** | Stable technical name of a topic (`jeux-video`), derived from its first label; never changes |
| **SSRF** | Server-side request forgery: making the server call an internal address; prevented in the feed check |
| **Stock** | Redis list `feed:{userId}` of up to 50 prepared article ids for a reader |
| **Stock version** | Redis counter `feed:{userId}:ver`, incremented before each invalidation or reset; a refill appends only if it is unchanged |
| **Strong event** | `like`, `save`, `share`, `hide` or a complete `read`; triggers an invalidation |
| **Stream** | Redis stream `events` carrying event and `user_deleted` messages to the recommender |
| **ULID** | Sortable 26-character id used for users (`u_…`) and server-generated events |
