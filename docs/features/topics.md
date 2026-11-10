# Topics

Topics readers choose at onboarding, and their administration. Code: `swipread.api.topic`.

## Model

| Table | Content |
| --- | --- |
| `app.topics` | `id`, `slug` (unique, stable), `label` (displayed) |
| `app.user_topics` | Topics chosen by each reader, `ON DELETE CASCADE` with the user |
| `app.article_topics` | Topics detected for each article by the ingestion, with a `confidence` |

The **slug** is the stable technical name of a topic. The recommender keys its fallback lists by slug (`fallback:{slug}` in Redis), so a slug never changes once created. The **label** is what readers see and can be renamed.

Eight topics are seeded by `db/seed/R__1_topics.sql` in every profile that loads the seed (`dev`, `test`).

## Reader endpoints

| Endpoint | Does |
| --- | --- |
| `GET /v1/topics` | All topics, sorted by label |
| `PUT /v1/me/topics` | Replaces the reader's choices (3 to 50 ids) |

`TopicService.replaceForUser` checks that every id exists (400 `"Unknown topic ids: [...]"` otherwise), deletes the previous choices and inserts the new ones in one transaction, then publishes a `UserTopicsChanged` Spring event. `TopicsChangedListener` in the `feed` package handles it **after the commit** and empties the reader's stock, so the next page reflects the new topics (see [Feed](feed.md#topic-change)). A rejected request changes nothing.

The minimum of three topics is enforced by Bean Validation on `UpdateTopicsRequest` (`@Size(min = 3, max = 50)`).

## Administration

| Endpoint | Service method |
| --- | --- |
| `GET /v1/admin/topics` | `TopicAdminService.list` |
| `POST /v1/admin/topics` | `TopicAdminService.create` |
| `PATCH /v1/admin/topics/{id}` | `TopicAdminService.relabel` |

- **Counts.** `articleCount` and `readerCount` come from one native query with two correlated `count(*)` sub-queries on `article_topics` and `user_topics`.
- **Slug.** `Slugs.of(label)`: Unicode decomposition (NFD), diacritics removed, lowercase, every run of non-alphanumeric characters replaced by one dash, leading and trailing dashes removed. « Santé & bien-être » gives `sante-bien-etre`. A label that yields an empty slug (no letter or digit) answers 400.
- **Uniqueness.** Two labels with the same slug conflict (409): « Jeux Vidéo » and « jeux video » are the same topic. The unique constraint also catches concurrent creations.
- **Rename.** Only the label changes; the response is re-read with its counts. 404 for an unknown id.
- There is no delete endpoint: deleting a topic would orphan readers' choices and the recommender's fallback lists.

A new topic is offered at onboarding immediately; readers' existing choices are untouched.

## Tests

`OnboardingIntegrationTest` (list, replacement, three-topic minimum, `/v1/me`), `AdminTopicsIntegrationTest` (creation and slug, 409 on the same slug, counts, rename keeping the slug, 403 for readers), `SlugsTest`, and the topic-change cases of `FeedIntegrationTest`.
