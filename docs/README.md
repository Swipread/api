# Swipread API documentation

Developer documentation for the Swipread backend. New here? Read [Getting started](getting-started.md), then [Architecture](architecture.md).

## Guides

| Page | Read it to… |
| --- | --- |
| [Getting started](getting-started.md) | Run the API locally, use Swagger UI and curl, inspect PostgreSQL and Redis, troubleshoot |
| [Architecture](architecture.md) | Understand the components, packages, security model, error handling and identifiers |
| [API reference](api.md) | Look up an endpoint: request, response, validation, errors |
| [Flows](flows.md) | Follow the main sequences end to end (first feed, strong signal, refill race, token renewal, deletion, source check) |
| [Database](database.md) | Schema diagram, tables, partitions, indexes, the recommender's role |
| [Configuration](configuration.md) | Properties, environment variables, profiles, production checklist |
| [Testing](testing.md) | Automated tests and the Bruno/Postman scenarios |
| [Deployment](deployment.md) | Build, tested container image, first start, operations, release checklist |
| [Decisions](decisions.md) | Architecture decision records, contract deviations, validated interpretations, open questions |
| [FAQ](faq.md) | Fix common problems |
| [Glossary](glossary.md) | Look up a term |

## Features

| Module | Covers |
| --- | --- |
| [Authentication](features/auth.md) | Sign-up, login, access and refresh tokens, rotation, logout, roles |
| [Account, saved and history](features/account.md) | Profile, per-article state, saved list, reading history, account deletion |
| [Topics](features/topics.md) | Topics, onboarding choices, slugs, topic administration |
| [Feed](features/feed.md) | Redis stock, paging, refill, fallback feed, invalidation, versioning |
| [Events](features/events.md) | Ingestion, validation, dedup, aggregation, strong events, outbox, partitions |
| [Recommendation](features/recommendation.md) | Recommender client and mock, events stream, what the recommendation team needs |
| [Sources](features/sources.md) | Source administration, feed check, SSRF protection |

## Scenarios

- [`bruno/`](bruno/): end-to-end scenario for Bruno
- [`postman/`](postman/): the same scenario for Postman and Newman
