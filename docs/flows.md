# Flows

End-to-end sequences across modules. Per-module details are in the [feature guides](README.md#features).

## A new reader: sign-up to first feed

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant A as API
    participant DB as PostgreSQL
    participant R as Redis
    participant Reco as Recommender

    C->>A: POST /v1/auth/register
    A->>DB: INSERT app.users, app.refresh_tokens
    A-->>C: 201 access token + Set-Cookie swipread_refresh
    C->>A: GET /v1/topics
    C->>A: PUT /v1/me/topics [1, 2, 3]
    A->>DB: replace app.user_topics
    Note over A,R: after commit: reset the (empty) stock
    C->>A: GET /v1/feed
    A->>R: LPOP feed:{u} 10 → empty
    A->>DB: fallback: fallback:{slug} lists, then recent articles
    A-->>C: 10 cards, reason "fallback"
    A->>Reco: background refill: POST /v1/recommendations (count 50)
    Reco-->>A: 50 ranked ids, requestId r_…
    A->>R: RPUSH feed:{u} (version checked)
    C->>A: GET /v1/feed
    A->>R: LPOP feed:{u} 10
    A-->>C: 10 cards, reason "similar"/"explore"…
```

## A strong signal changes the feed

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant A as API
    participant DB as PostgreSQL
    participant R as Redis
    participant Reco as Recommender

    C->>A: POST /v1/events [like a_42]
    A->>DB: INSERT app.events, upsert user_articles, INSERT event_outbox (one transaction)
    A->>R: INCR feed:{u}:ver, LTRIM feed:{u} 0 9
    A-->>C: 202
    A->>Reco: refill (exclude = the 10 kept)
    Reco-->>A: 40 ids reflecting the like
    A->>R: RPUSH if the version is unchanged
    Note over A,R: within ~200 ms the outbox relay publishes the like on the events stream
    R-->>Reco: XREADGROUP events → update the reader's profile
```

The next page still shows the 10 articles that were already queued, then the recomputed ones: scrolling stays smooth while the feed adapts.

## A refill races an invalidation

```mermaid
sequenceDiagram
    autonumber
    participant RF as Refill (started earlier)
    participant E as Event ingestion
    participant R as Redis

    RF->>R: GET feed:{u}:ver → 7
    RF->>RF: waiting for the recommender (≤ 300 ms)
    E->>R: INCR feed:{u}:ver → 8, LTRIM 0 9
    RF->>R: Lua: append only if ver = 7 → refused
    RF->>R: GET feed:{u}:ver → 8, recompute once
    RF->>R: Lua: append if ver = 8 → done
```

Without the version, the first batch (computed before the like) would have been appended after the invalidation.

## Token renewal with two tabs

```mermaid
sequenceDiagram
    autonumber
    participant T1 as Tab 1
    participant T2 as Tab 2
    participant A as API

    Note over T1,T2: both hold cookie v1, access token expired
    T1->>A: POST /v1/auth/refresh (v1)
    A-->>T1: 200 + Set-Cookie v2 (v1 revoked, replaced by v2)
    T2->>A: POST /v1/auth/refresh (v1, 2 s later)
    A-->>T2: 200, no new cookie (grace period)
    Note over A: v1 presented again after 10 s → reuse → family revoked → 401 for everyone
```

## Account deletion

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant A as API
    participant DB as PostgreSQL
    participant R as Redis
    participant Reco as Recommender

    C->>A: DELETE /v1/me
    A->>DB: DELETE app.events (user), INSERT outbox user_deleted, DELETE app.users (cascades)
    A->>R: DEL feed:{u}, :req, :lock, :meta, :ver, seen:{u}
    A-->>C: 204
    Note over A,R: outbox relay publishes user_deleted
    R-->>Reco: user_deleted → erase reco.user_profiles
    C->>A: any request with the old access token
    A-->>C: 401 (the account no longer exists)
```

## Adding a source

```mermaid
sequenceDiagram
    autonumber
    participant Ad as Admin client
    participant A as API
    participant P as FeedAddressPolicy
    participant F as Remote feed

    Ad->>A: POST /v1/admin/sources
    A->>A: feedUrl already used? → 409
    loop up to 5 redirects
        A->>P: check scheme and port
        A->>P: resolve host (all addresses public?) → else 422 "Feed address is not allowed"
        A->>F: GET (5 s connect, 10 s answer, ≤ 5 MB)
    end
    A->>A: parse RSS/Atom (no DOCTYPE), count entries
    alt valid feed with entries
        A-->>Ad: 201 source, active
    else
        A-->>Ad: 422 with the reason
    end
```
