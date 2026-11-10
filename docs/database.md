# Database

One PostgreSQL 17 database with the pgvector extension, split into two schemas:

- `app`: business data, written by the API, the ingestion and admins;
- `reco`: the recommendation service's internal data.

Source of truth: the Flyway migrations in `src/main/resources/db/migration`. Keep this page in sync with every new migration.

| Migration | Content |
| --- | --- |
| V1 | Schema from the backend / recommender contract (tables, indexes, the October 2026 partition of `app.events`) |
| V2 | `app.events_default` partition |
| V3 | `app.users.role` (`READER` or `ADMIN`) |
| V4 | `app.user_articles.opened_at` and its index, backfilled from `app.events` |
| V5 | `swipread_reco` group role and its privileges |
| V6 | `app.event_outbox` |
| V7 | `app.refresh_tokens` |

Repeatable migrations load data: `db/seed` (8 topics and the `admin@swipread.dev` account; `dev` and `test` profiles) and `db/dev-data` (300 sample articles; `dev` only).

## Diagram

Entity names are written `schema_table` (Mermaid does not accept dots). Solid lines are foreign keys; dotted lines are logical links without a constraint.

```mermaid
erDiagram
    app_users {
        text id PK "u_..."
        text email UK
        text password_hash "bcrypt"
        text display_name
        text locale "default fr-FR"
        timestamptz created_at
        text role "READER or ADMIN (V3)"
    }
    app_topics {
        serial id PK
        text slug UK
        text label
    }
    app_user_topics {
        text user_id PK, FK
        int topic_id PK, FK
        timestamptz created_at
    }
    app_sources {
        serial id PK
        text name
        text site_url
        text feed_url UK
        text language
        text license "nullable"
        boolean active
    }
    app_articles {
        text id PK "a_..."
        int source_id FK
        text url UK
        text title
        text summary
        text image_url "nullable"
        text author "nullable"
        text language
        int word_count
        timestamptz published_at
        timestamptz ingested_at
        vector embedding "vector(384), nullable, not mapped by the API"
    }
    app_article_topics {
        text article_id PK, FK
        int topic_id PK, FK
        real confidence
    }
    app_events {
        text event_id PK "e_..., client-generated"
        timestamptz occurred_at PK "partition key"
        text user_id
        text session_id
        text type
        text article_id "nullable"
        text request_id "nullable"
        int position "nullable"
        jsonb payload
        timestamptz received_at
    }
    app_user_articles {
        text user_id PK, FK
        text article_id PK, FK
        timestamptz first_seen_at
        int total_dwell_ms
        real max_scroll "nullable"
        timestamptz liked_at "nullable"
        timestamptz saved_at "nullable"
        timestamptz hidden_at "nullable"
        timestamptz opened_at "nullable (V4)"
    }
    app_event_outbox {
        bigserial id PK
        text message "JSON published on the stream"
        timestamptz created_at
    }
    app_refresh_tokens {
        uuid id PK
        text user_id FK
        uuid family_id
        text token_hash UK "SHA-256"
        timestamptz created_at
        timestamptz expires_at
        timestamptz revoked_at "nullable"
        uuid replaced_by FK "nullable"
    }
    reco_user_profiles {
        text user_id PK
        vector embedding "vector(384)"
        int events_count
        timestamptz updated_at
    }
    reco_model_runs {
        serial id PK
        text version "e.g. content-v1"
        timestamptz started_at
        timestamptz finished_at "nullable"
        jsonb metrics "nullable"
    }

    app_users ||--o{ app_user_topics : "chooses (cascade)"
    app_topics ||--o{ app_user_topics : "is chosen"
    app_sources ||--o{ app_articles : "publishes"
    app_articles ||--o{ app_article_topics : "is tagged (cascade)"
    app_topics ||--o{ app_article_topics : "tags"
    app_users ||--o{ app_user_articles : "interacts (cascade)"
    app_articles ||--o{ app_user_articles : "aggregate (cascade)"
    app_users ||--o{ app_refresh_tokens : "sessions (cascade)"
    app_users ||..o{ app_events : "user_id, no FK"
    app_articles ||..o{ app_events : "article_id, no FK"
    app_users ||..o| reco_user_profiles : "user_id, no FK"
```

## Tables

| Table | Written by | Content |
| --- | --- | --- |
| `app.users` | API | Accounts, preferred locale, role |
| `app.topics` | admins (seeded in `dev`/`test`) | Topics offered at onboarding |
| `app.user_topics` | API | Topics chosen by each reader |
| `app.sources` | admins | Ingested RSS/Atom feeds, with their reuse licence and status |
| `app.articles` | ingestion | Metadata, summary, word count, embedding |
| `app.article_topics` | ingestion | Topics detected for each article, with a confidence score |
| `app.events` | API | Raw event history (`POST /v1/events`), partitioned by month |
| `app.user_articles` | API | Per reader and article: dwell time, max scroll, like, save, hide, latest opening |
| `app.event_outbox` | API | Stream messages waiting to be published, deleted once published; internal |
| `app.refresh_tokens` | API | Hashed refresh tokens with their family, expiry, revocation and replacement; internal |
| `reco.user_profiles` | recommender | Each reader's taste vector |
| `reco.model_runs` | recommender | Training runs and their metrics |

`app.events` is the source of truth: `app.user_articles` and `reco.user_profiles` can be rebuilt from it.

## Foreign keys and deletion

- `ON DELETE CASCADE`: deleting a user deletes their `user_topics`, `user_articles` and `refresh_tokens`; deleting an article deletes its `article_topics` and `user_articles`.
- `app.events` has no foreign key on purpose: inserts stay fast and the history survives an article's deletion. Account deletion removes the user's events explicitly (`DELETE FROM app.events WHERE user_id = …`, served by `events_user_idx`).
- `reco.user_profiles.user_id` has no foreign key to `app.users` either; the recommender erases it on the `user_deleted` message.
- `app.refresh_tokens.replaced_by` points to another token, `ON DELETE SET NULL`.

## Partitioning of `app.events`

- Range partitions on `occurred_at`, one per month: `app.events_YYYY_MM`.
- The primary key `(event_id, occurred_at)` deduplicates client resends.
- `app.events_default` (V2) catches events outside existing partitions instead of failing the insert. If it already holds rows of a month, creating that month's partition fails: the job logs the error and the rows must be moved by hand.
- At startup and every night at 03:00 UTC, `EventPartitionJob` creates the partitions of the current and next month.
- JDBC sessions are in UTC, so partition bounds are UTC midnights.

## Indexes

| Index | Used by |
| --- | --- |
| `articles_published_idx` (`published_at DESC`) | Fallback feed: most recent articles |
| `articles_embedding_idx` (HNSW, `vector_cosine_ops`) | Nearest-neighbour search by the recommender (`<=>`) |
| `events_user_idx` (`user_id, occurred_at DESC`) | A user's events, account deletion |
| `user_articles_saved_idx` (`user_id, saved_at DESC`, partial `saved_at IS NOT NULL`) | `GET /v1/me/saved` |
| `user_articles_opened_idx` (`user_id, opened_at DESC`, partial `opened_at IS NOT NULL`) | `GET /v1/me/history` |
| `refresh_tokens_family_idx` (`family_id`) | Revoking a token family |
| `refresh_tokens_expires_idx` (`expires_at`) | Nightly purge of expired tokens |

## Access from the API

- The API never maps the `embedding` column: the ingestion writes it, the recommender reads it.
- JPA (`ddl-auto: validate`) for accounts, topics, sources and articles; `JdbcTemplate` for events, per-article state, the outbox and refresh tokens.

## Recommender role

Migration V5 creates the group role `swipread_reco`, **without login**:

| Schema | Privileges |
| --- | --- |
| `app` | `SELECT` on every table, including tables created later by the API (default privileges: future migrations, monthly partitions of `app.events`). On `app.users`, only `id`, `locale` and `created_at`: no email, password hash or display name. No access to `app.event_outbox` and `app.refresh_tokens` |
| `reco` | Read-write on tables and sequences, and `CREATE` on the schema |

The login role the recommender connects with is created by operations and joined to this group (see [Configuration](configuration.md#production-checklist)). A PostgreSQL role is global to the server: if `swipread_reco` does not exist and the migration user lacks `CREATEROLE`, V5 fails with an explicit message, and an administrator must run `CREATE ROLE swipread_reco NOLOGIN` first.

## Promoting an administrator

There is no endpoint for it: `UPDATE app.users SET role = 'ADMIN' WHERE email = '…'`. It takes effect at the account's next token renewal.
