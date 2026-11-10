# Account, saved articles and history

The reader's own data. Code: `swipread.api.user`.

## Endpoints

| Endpoint | Service |
| --- | --- |
| `GET /v1/me`, `PATCH /v1/me` | `UserService` |
| `DELETE /v1/me` | `UserService.delete` |
| `GET /v1/me/saved` | `SavedArticleService` |
| `GET /v1/me/history` | `HistoryService` |

`PUT /v1/me/topics` belongs to [Topics](topics.md).

## Profile

`GET /v1/me` returns the account (`id`, `email`, `displayName`, `locale`, `createdAt`, `role`) and the chosen topics. `PATCH /v1/me` only changes `displayName` (trimmed, 1–50 characters) and returns the full profile.

## Per-article state

The reader's relation to each article lives in `app.user_articles`, one row per (user, article), maintained from events (see [Events](events.md#aggregation)):

| Column | Set by |
| --- | --- |
| `first_seen_at` | Earliest event on the article |
| `total_dwell_ms` | Sum of `view.dwellMs` |
| `max_scroll` | Highest `read.scrollDepth`, when sent |
| `liked_at` | `like`; cleared by `unlike` |
| `saved_at` | `save`; cleared by `unsave` |
| `hidden_at` | `hide` |
| `opened_at` | Latest `open` or `read` |

`UserArticleStates` reads `liked` and `saved` for a list of articles in one query; the feed uses it to fill the `liked` and `saved` flags of each card.

## Saved articles

`SavedArticleService.page(userId, cursor, limit)`:

```sql
SELECT article_id, saved_at, liked_at IS NOT NULL AS liked
FROM app.user_articles
WHERE user_id = ? AND saved_at IS NOT NULL AND hidden_at IS NULL
  AND (cursor IS NULL OR (saved_at, article_id) < (cursor.at, cursor.articleId))
ORDER BY saved_at DESC, article_id DESC
LIMIT limit + 1
```

- **Keyset pagination.** The cursor is the position of the last row returned, `(date, articleId)`, encoded as base64url (`ArticleCursor`). It stays correct when new articles are saved meanwhile, unlike an offset. An undecodable cursor answers 400 `"Invalid cursor"`.
- One extra row is read to know whether a next page exists; `nextCursor` is omitted on the last page.
- Articles are then loaded by `ArticleQueryService.hydrate` (content, source name, topics sorted by confidence). An article deleted meanwhile is skipped.
- The partial index `user_articles_saved_idx (user_id, saved_at DESC) WHERE saved_at IS NOT NULL` serves the query.

Unsaving is an `unsave` event, not a `DELETE`: the event log stays the source of truth and the recommender receives the signal.

## History

`HistoryService` works the same way on `opened_at` (index `user_articles_opened_idx`), and also returns `saved`. An article enters the history with an `open` or `read` event; `readAt` is the latest opening, so a reopened article moves back to the top without duplicates. Hidden articles are excluded.

A late event (an `open` sent after a more recent one) does not move `opened_at` backwards: the upsert keeps the maximum.

## Account deletion

`UserService.delete(userId)`:

1. In one transaction (`EventErasure`): delete the user's rows from `app.events`, write a `user_deleted` message to the outbox, then delete the account. Cascades remove `user_topics`, `user_articles` and `refresh_tokens`.
2. After the commit: delete the Redis keys `feed:{userId}`, `:req`, `:lock`, `:meta`, `:ver` and `seen:{userId}`.
3. The outbox relay publishes `user_deleted` on the `events` stream; the recommender erases `reco.user_profiles` for that user.

The access token still held by the client is refused at the next request (the account no longer exists). A refill that was already running may recreate a stock key for the deleted user; nobody can read it and it expires after 6 hours.

## Tests

`AccountIntegrationTest` (rename, deletion across database, Redis and the stream, token refused afterwards, email reusable), `SavedArticlesIntegrationTest` (cursor paging, hidden articles excluded, `unsave`, topics sorted by confidence, `liked`), `HistoryIntegrationTest` (opened only, order by latest opening, late events, hidden excluded, cursor), `UserArticleStatesIntegrationTest`.
