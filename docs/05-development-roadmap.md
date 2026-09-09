# Development Roadmap

The original plan was twelve phases, to be finished in order: domain model, PostgreSQL schema, Flyway
migrations, JPA entities, DTOs, MapStruct mappers, repositories, services, REST controllers,
validation and exception handling, tests, documentation.

**The twelve are done.** This document is kept because the reading order points here, and because
what a plan turned out to need is worth more than the plan: below is where each phase landed, what
had to be added that the plan did not foresee, and what is knowingly still open.

## Where each phase landed

| Phase | Where it lives now |
| --- | --- |
| 1–2 Domain model and schema | `domain/model` (framework-free records) and `02-domain-model.md`; the schema in `03-database.md`. |
| 3 Migrations | `src/main/resources/db/migration`, `V1`–`V7`. Hibernate runs `ddl-auto: validate` in every profile, so this is the only way the schema changes. |
| 4–6 Entities, DTOs, mappers | `infrastructure/persistence/entity`, `application/dto`, `application/mapper`. |
| 7–8 Repositories and services | `infrastructure/persistence/repository` and `application/service`, impls package-private behind interfaces. |
| 9–10 Controllers, validation, errors | `infrastructure/web`; every endpoint in `04-rest-api.md`, every error through `GlobalExceptionHandler`. |
| 11 Tests | One class per layer, not per production class. See **Testing** in `CLAUDE.md`. |
| 12 Documentation | This `docs/` directory, plus `README.md` and `CLAUDE.md`. |

## What the plan did not foresee

Each of these arrived because something was wrong, not because a phase called for it:

- **`inventory_balance` (`V3`).** Summing the ledger on every read does not hold up. The projection
  was added with the conditional `UPDATE ... WHERE` idiom, because a read-then-write lets two
  concurrent operations overdraw the same stock.
- **Security.** Keycloak as resource server, roles per verb, and `STOCK_ADJUST` on top of
  `STOCK_WRITE` for the one write with no counterpart document.
- **The master data channel and the inbox (`V4`–`V6`).** Consuming what `mto-configuration` publishes
  is idempotent through `inbox_message`, and ordered through the `project.source_sequence_number`
  watermark. See `06-messaging.md`.
- **The Redis cache.** Catalogue reads by id only, never stock, and the whole thing optional: with
  `app.cache.enabled=false` the application serves everything from the database.
- **Distributed tracing.** The trace born in `mto-gateway` continues here, across the RabbitMQ hop
  included.
- **Hibernate Envers (`V7`).** The `updated_by` columns say who touched a row last; they do not say
  what it said before. Seven `<table>_aud` twins and `audit_revision` do. See `07-auditing.md`.

## Knowingly open

Not a backlog — these are decisions already taken and recorded, listed here so nobody rediscovers
them as bugs:

- **A `project` written by a master data event leaves no Envers revision.** The watermark has to be
  checked inside the writing statement, and Envers cannot see native SQL. `project_aud` covers the
  REST path only. Closing it would mean giving up the atomic check, which is the worse trade.
  (`07-auditing.md`)
- **Seven of the eight published entities have no handler.** Stations, tracks, profiles, cantilevers,
  steady arms, disconnectors and section insulators describe catenary geometry and have no equivalent
  in a warehouse. Their events are recorded in the inbox with their payload and skipped, so a handler
  written later can reprocess what was stored. (`06-messaging.md`)
- **Historical stock is not exposed.** `StockMovementRepository.calculateSignedQuantity` answers "how
  much was there on day X" and is tested against a real Postgres, but no service or endpoint wraps it.
  The current-stock reads go to the projection, which only stores the now.
- **This service consumes and never publishes.** There is no outbox here and no `RabbitTemplate`. If
  something outside ever has to react to a stock movement, that half has to be built.
