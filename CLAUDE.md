# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

MTO Stock: a Spring Boot 4 / Java 25 inventory API for a make-to-order warehouse (railway catenary construction). The warehouse stores individual component materials, not assembled catenary sets; assemblies are virtual products defined by a Bill of Materials (BOM) and have no stock of their own.

Read `docs/` in order for full domain/architecture context: `00-project-overview.md`, `01-architecture.md`, `02-domain-model.md`, `03-database.md`, `04-rest-api.md`, `05-development-roadmap.md`, `06-messaging.md`, `07-auditing.md`. Those documents (plus this file) are the source of truth for the project — `03-database.md` in particular documents the full schema, enums, constraints and indexes and should be checked before changing persistence code.

## Commands

Build / test (use `mvnw.cmd` instead of `./mvnw` on Windows PowerShell):

```bash
./mvnw compile                                    # compile only
./mvnw test                                       # full test suite (Docker for Testcontainers, or TEST_DATABASE_* pointing at a PostgreSQL)
./mvnw test -Dtest=BusinessLayerTest               # single test class
./mvnw test -Dtest=BusinessLayerTest#stockMovementEntryIncreasesPhysicalAndAvailableBalance   # single test method
./mvnw spring-boot:run                            # run the app (needs SPRING_PROFILES_ACTIVE + DATABASE_* env vars, see README)
```

Local environment. PostgreSQL, Redis, RabbitMQ, Keycloak and the trace collector live in the
sibling repository `mto-platform`, which is the single shared local infrastructure for the whole
domain — one broker, so the master data channel cannot silently split in two as it did when each
repository brought up its own:

```bash
cd ../mto-platform && docker compose --profile all up -d && ./keycloak/apply-partials.sh
```

`compose.yaml` here holds **only the application**, for running a local build against that
infrastructure:

```bash
cp .env.example .env   # then edit credentials
docker compose up -d --build
```

- Flyway runs automatically on startup against `src/main/resources/db/migration`; Hibernate is `ddl-auto: validate` in every profile, so schema changes always go through a new Flyway migration, never through entity annotations alone. A migration that changes a column on one of the seven audited tables must change its `<table>_aud` twin in the same migration (nullable, no constraints) — see **Auditing** below.
- Swagger UI: `http://localhost:8080/swagger-ui.html`; OpenAPI JSON: `/v3/api-docs`; Actuator health/info/metrics/prometheus under `/actuator/*`.
- Profiles: `dev`, `test`, `prod` (`application-*.yml`), selected via `SPRING_PROFILES_ACTIVE`.

## Architecture

The code is organized as three layers, each with its own package root under `com.alejandro.mtostock`, rather than the flatter controller/service/repository split described in `docs/01-architecture.md`:

- `domain/model` — framework-free domain types (Java records, e.g. `Material`, `Warehouse`, `Reservation`, `StockMovement`, `Quantity`). These validate invariants in compact constructors via `DomainValidations` and have no JPA/Spring annotations.
- `application` — `dto` (API request/response types), `service` + `service.impl` (business logic, package-private impls behind public interfaces), `mapper` (MapStruct interfaces mapping between DTOs, domain records and JPA entities), `exception` (domain/business exceptions, all funneled through `GlobalExceptionHandler`).
- `infrastructure` — `persistence.entity` (JPA `@Entity` classes — **note these share class names with `domain.model`**, e.g. both `domain.model.Material` and `infrastructure.persistence.entity.Material` exist; always check the import when touching a `Material`/`Warehouse`/`Assembly`/etc. reference), `persistence.repository` (Spring Data repositories + JPA Specifications for filtering), `persistence.specification`, and `web.controller` / `web.exception` (REST layer).

DTOs only cross the API boundary; JPA entities are never exposed directly. MapStruct (`application/mapper`) generates all entity↔DTO and entity↔domain mappings; `EntityReferenceFactory` supplies MapStruct with lightweight entity references (id-only) for relationship fields instead of loading full associations.

### Stock model

Stock is never stored directly on `material`/`warehouse`. It is derived from an append-only ledger (`stock_movement`) plus reservations, and is kept fast to read via a materialized projection:

- `stock_movement` is the audit ledger: every entry, output, adjustment and transfer is one signed row (`ENTRY`/`POSITIVE_ADJUSTMENT`/`INCOMING_TRANSFER` are `+`, `OUTPUT`/`NEGATIVE_ADJUSTMENT`/`OUTGOING_TRANSFER` are `-`). Transfers are two linked rows (`related_movement_id`) rather than a separate transfer table.
- `inventory_balance` (added in `V3__add_inventory_balance_projection.sql`) is a per material/warehouse projection with `physical_quantity`, `reserved_quantity` and `available_quantity` (`available = physical - reserved`, enforced by a DB check constraint), plus an optimistic-lock `version` column. `InventoryBalanceRepository`/`InventoryBalanceServiceImpl` update it atomically (conditional `UPDATE ... WHERE` row counts, not read-then-write) whenever a movement or reservation changes it, so reads never need to sum `stock_movement`/`reservation` history. `StockCalculationServiceImpl` reads from this projection, not from `stock_movement`, for `calculatePhysicalStock`/`calculateReservedStock`/`calculateAvailableStock`.
- `reservation.status` (`ACTIVE`/`RELEASED`/`CANCELLED`/`CONSUMED`) drives `reserved_quantity`; only `ACTIVE` reservations reduce availability.
- **Consuming a reservation goes through the ledger.** `ReservationEngine.consume` only moves the balance (physical and reserved down); whoever calls it writes the `OUTPUT` with the reservation in the same transaction: `StockMovementServiceImpl.registerOutput` with a `reservationId`, or `registerReservationConsumption`, which is what `POST /reservations/{id}/consume` runs (`ReservationServiceImpl.consume`) — the reservation's material, warehouse, project and quantity, no reference, no notes, no new active checks, no idempotency key, no event. Never call the engine's `consume` alone: until `V11` that is what `/consume` did, and the ledger stopped adding up to the balance; `V11__backfill_outputs_of_consumed_reservations.sql` wrote the missing outputs.
- Assemblies never have stock — availability is computed on demand from the BOM (`assembly_component`) against current component stock (`BOMCalculationService`).
- **A BOM is keyed by material.** A material appears once (`uq_assembly_component_assembly_material`; `@UniqueComponentMaterials` makes a repeated one a `400` on `components`, and `InventoryValidationService.validateAssemblyComponentsAreDistinct` a `422` `ASM-001` for callers that skip the controller). `AssemblyMapper.updateEntity` maps the header only; `AssemblyServiceImpl.update` replaces the list matched by material (`replaceComponents`: the line of a material that stays keeps its id and only its quantity changes, by `compareTo`; the others are removed or added) and flushes before mapping the response. Adding the requested lines to the old ones hit the unique constraint (500).

### Idempotent writes

`POST /reservations` and `POST /movements/outputs` take an optional `Idempotency-Key` header (1 to 255
visible ASCII characters). `mto-maintenance` sends it so that a retry after a lost answer never
reserves or takes the material out twice. Full contract in `docs/04-rest-api.md`, table in
`docs/03-database.md`.

- `IdempotentRequestService.claim` runs **first**, inside the write's own transaction: an `on conflict
  do nothing` insert into `idempotent_request`, unique on `(operation, created_by, idempotency_key)` —
  the inbox's row-count idiom. `1`: the write runs, and `complete` records what it created in the same
  transaction. `0`: the stored row answers. With the same body fingerprint the service returns what
  was created, as it is now, **without validating stock again** (the first request may have taken all
  of it); with another body it is 409 `IDEM-001` and nothing is written.
- A write that fails rolls its claim back, so a retry after a rejection runs as a first request. A
  concurrent request with the same key waits on the unique index until the first one ends.
- The key belongs to the authenticated caller (`created_by`) and to the operation. Without a key,
  nothing changes. The fingerprint is SHA-256 over the request record's components that are set,
  name and value, decimals by value — so an optional field added later does not change it. A
  component marked `@IdempotencyIgnored` (`reservedAt`, `occurredAt`) does not count: a retry stamped
  with its own time is the same request, and the first one's date stands.
- Keys expire: `IdempotencyPurgeConfiguration` deletes, once a day and in batches, the ones first used
  more than `app.idempotency.retention` ago (30 days). A retry after that is a new request — the
  contract clients retry within; `mto-maintenance` retries what is left without an answer every few
  minutes. The tests switch the job off (`app.idempotency.purge.enabled=false`) and call the purge.
- The table is written only with native SQL and is not audited.

### Auditing

Two layers, answering different questions. The `created_at`/`updated_at`/`created_by`/`updated_by`
columns (Spring Data JPA auditing) say who touched a row **last**; Hibernate Envers keeps the
**history** of previous values. Both resolve the actor through `configuration/AuditActorResolver`, so
they cannot disagree about who wrote something — the `system` vs `unknown` distinction lives there.
Full detail in `docs/07-auditing.md`.

- **Audited (7)**: `Material`, `Supplier`, `Warehouse`, `Project`, `Assembly`, `AssemblyComponent`,
  `Reservation` — one `<table>_aud` twin each, plus `audit_revision` (a custom `@RevisionEntity`
  replacing `REVINFO`) in `infrastructure/persistence/audit`.
- **Not audited (5), on purpose**: `StockMovement` is already an immutable append-only ledger, so a
  twin would double the biggest table for no new information; `InventoryBalance`, `InboxMessage` and
  `IdempotentRequest` are written **only** with native SQL, which Envers cannot see, so their twins
  would sit empty and read as "never changed"; `OutboxMessage` (`V10`) is written only by the outbox
  and its history is itself. `@Audited` therefore goes on each entity and **never**
  on `AuditableEntity`, which would sweep in the four that extend it and stop the application from
  booting under `ddl-auto: validate`. `JpaEntityModelTest` guards the split.
- **Known gap**: a `project` changed by a master data event leaves no revision —
  `ProjectRepository.upsertFromMasterData`/`deactivateFromMasterData` are native SQL because the
  sequence watermark has to be checked inside the writing statement. `project_aud` covers the REST
  path only.
- History is read at `GET /api/v1/inventory/<resource>/{id}/revisions` (not `/history`: `/movements`
  already means the stock ledger). No new security rule — `GET` under the API prefix is already
  `STOCK_READ`.

### Messaging

`mto-stock` consumes the master data change events that `mto-configuration` publishes to RabbitMQ
(`mto.master-data.exchange`, routing key `mto.master-data.#`, own queue `mto.stock.master-data.queue`
with its own DLX/DLQ). Every message is logged and then routed by entity name; **one** of the eight
entities has business logic behind it (`execution-package` → `project`, see below), and the other
seven are logged and ignored on purpose.

- `configuration/rabbitmq` — `MasterDataRabbitProperties` (`app.rabbitmq.master-data.*`) and
  `RabbitMqConfiguration` (topology, JSON converter, listener factory). The whole block is gated on
  `app.rabbitmq.enabled`, the consumer additionally on `app.rabbitmq.master-data.listener-enabled`;
  with the first off the application starts without a broker, which is what the tests rely on.
- `infrastructure/messaging/rabbitmq` — contract names/headers and the thin `MasterDataEventConsumer`.
- `application/dto/messaging` — the message contract, plus `MasterDataEntityNames` (the eight entity
  names `mto-configuration` publishes: all railway infrastructure, none matching a `mto-stock`
  entity one to one).
- `MasterDataEventHandler` is the transport/application boundary and has a single implementation,
  `DispatchingMasterDataEventHandler`, which logs each change and routes it by entity name.
  **`MasterDataEntityHandler` is where the business logic goes** — one `@Service` per entity, picked
  up from the context with nothing to register. `ExecutionPackageMasterDataHandler` is the only one:
  it upserts a `project` from an execution package and deactivates it on `DELETED` (never deletes —
  `reservation`/`stock_movement` reference it with `on delete restrict`), matching on
  `project.source_service` + `source_entity_id` (added in `V5`, `NULL` for projects created through
  the API) with the code derived as `EP-<sourceId>`. Late events are discarded against the
  `project.source_sequence_number` watermark (`V6`) from the `sequenceNumber` header, compared
  inside the writing statement's `where` — never read-then-write; a missing sequence still applies
  and keeps the stored watermark, and a deletion advances it even when the row is already inactive. The other seven entities have no handler and
  an unknown entity is never an error (the queue is bound to `mto.master-data.#` and gets
  everything), while a message with no `data`, `entityName` or `operation` is rejected to the DLQ by
  the consumer. An entity handler runs inside the inbox transaction, so it must not open its own.

Consumption is idempotent through an **inbox** (`inbox_message`, added in
`V4__create_inbox_message_table.sql`), the consumer-side counterpart of the publisher's outbox:

- The idempotency key is the envelope's `operationId`, falling back to the AMQP `message_id` header;
  a message with neither is rejected straight to the DLQ. The guarantee is the unique constraint on
  `(message_id, source_service)`, not any check in code — `InboxMessageRepository` claims and marks
  with conditional native `UPDATE`s and an `on conflict` insert, the same row-count idiom as
  `InventoryBalanceRepository`, because a read-then-write lets two concurrent deliveries through.
- `MasterDataEventConsumer` → `MasterDataEventProcessor` → `InboxMessageService` → the handler. The
  consumer never calls `MasterDataEventHandler` directly; the inbox decides whether it runs at all.
- Recording, claiming, the handler and the `PROCESSED` mark share one transaction.
  `InboxMessageService.recordFailure` is `REQUIRES_NEW` and must be called **after** that
  transaction has finished (that is why `IdempotentMasterDataEventProcessor` is not transactional):
  calling it from inside deadlocks on the row the outer transaction holds.
- `payload` is `json`, not `jsonb`, so the stored bytes stay identical to what arrived and keep
  matching `payload_hash`.

`configuration/messaging` verifies the `messageSignature` header over the received bytes before the
consumer uses the message (`app.messaging.signature.secret` — the same value as in
`mto-configuration` — plus `.mode`: `DISABLED`/`OPTIONAL`/`REQUIRED`). A bad signature is always
rejected to the DLQ; a message that *cannot* be verified (unsigned, or signed with an algorithm this
side cannot compute because the secrets differ) is only rejected under `REQUIRED`. The default is
`OPTIONAL` because `REQUIRED` with mismatched secrets sends every valid message to the DLQ.

The master-data contract is owned by `mto-configuration` — check `docs/06-messaging.md` (and that
repository's `README_MESSAGING.md`) before changing anything under `application/dto/messaging`.

Producer of its own events for `mto-notification` (`mto.stock.exchange`, routing key
`mto.stock.<entity>.<event>`, no queue here). **`DomainEventPublisher.publish` is the only door, and
it is called inside the business transaction**: the event goes to `outbox_message` (`V10`) with the
change and the relay publishes it afterwards with publisher confirms (`OutboxRabbitPublisher`
refuses to start without them). The outbox is the copy of `mto-configuration`'s `core/outbox` that
`mto-maintenance` carries too (`infrastructure/messaging/outbox`, every piece a `@Bean` of
`configuration/outbox/OutboxConfiguration`, all gone with `app.rabbitmq.enabled=false`, when the
publisher is the `NoOpDomainEventPublisher`). The hooks: `InventoryBalanceServiceImpl` publishes
`material.below-minimum` when the **total** available of a material (the sum over warehouses, the
same view as `GET /materials/{id}/stock` without a warehouse) crosses below `minimumStockLevel` —
only at the crossing, never while already below, never for a material with no minimum; every
operation that reduces the available (`decreasePhysicalAndAvailable`, `reserve`, and `transfer`
although it never crosses) **locks the material row first** (`MaterialRepository.findByIdForUpdate`,
lock order material → `inventory_balance`) so that concurrent outputs serialize and exactly one sees
the crossing; a transfer is one balance operation (`InventoryBalanceService.transfer`), not an output
plus an entry, precisely so it never publishes. `ReservationEngineImpl.cancel/release` publish
`reservation.cancelled`/`released` with `createdBy` (who created it: `service-account-mto-maintenance-svc`
for the ones `mto-maintenance` makes). `StockMovementServiceImpl.registerAdjustment` publishes
`adjustment.registered` with the direction. The names and `values` of every event live in
`StockEvents` and nowhere else, with the same material, warehouse and project keys in all of them;
the envelope is the `AsynchronousMessage` of `mto-configuration` plus `actor`
(`PERSON`/`SERVICE`/`SYSTEM`, from the token) and `correlationId` (`X-Correlation-Id` of the request,
else the master-data message id, as Envers stores it), both read by `MessageContextResolver` when
the event is created. **Keys are only added**; a new key or event changes its example in
`docs/messaging/examples/` in the same commit (`MessagingContractExamplesTest` compares them with
the real factory; `MESSAGING_EXAMPLES_WRITE=true` regenerates them). `DomainEvent` rejects any key
that smells like a secret.

### Testing

- `PostgreSQLTestContainer` (in `support/`) is the shared base for integration tests needing a real Postgres (`postgres:17-alpine` via Testcontainers — the same version `mto-platform` runs, so CI and local do not test against different engines) with Flyway migrations applied — extend it rather than mocking the datasource for repository/persistence tests. Without Docker, `TEST_DATABASE_URL`/`TEST_DATABASE_USERNAME`/`TEST_DATABASE_PASSWORD` point it at a PostgreSQL instead, as in `mto-maintenance`; the classes that extend it do not carry `@Testcontainers(disabledWithoutDocker = true)`, which would switch them off even then.
- `MtoStockApplicationTests` is the only `@SpringBootTest`: it boots the whole context against a real Postgres (`PostgreSQLTestContainer`), with no service replaced by a mock, and asserts every business service bean is present. It needs a real PostgreSQL for that reason. It used to mock the ten services and exclude `DataSourceAutoConfiguration`, which is why nothing caught that the 16 `@Service` impls carried `@ConditionalOnBean(XRepository.class)` — an annotation Spring only supports on auto-configurations, always false on a scanned `@Service`, so no service bean was ever created and the packaged application could not start. Do not reintroduce it.
- Tests are consolidated **one class per layer**, not one class per production class: `BusinessLayerTest` (all services), `RestControllerLayerTest` + `ReservationControllerMockMvcTest` (controllers), `PersistenceLayerTest` + `InventoryRepositoryDataJpaTest` + `InboxMessageRepositoryDataJpaTest` (repositories), `InboxIdempotencyDataJpaTest` (idempotency end to end on real Postgres: the inbox, and the reservations and outputs written with an `Idempotency-Key`; also what only real SQL decides: a consumed reservation leaves the ledger and the balance agreeing, and a BOM is replaced on the real unique constraint), `EnversAuditDataJpaTest` (change history end to end — **it disables the test transaction on purpose: Envers writes at transaction completion, not on flush, so a rolled-back slice test would record nothing and look like Envers is broken**), `MapperLayerTest` (mappers), `MessagingLayerTest` (RabbitMQ contract, consumer and topology; the own exchange, the envelope, the actor, the correlation, the signer and the outbox publisher), `MessagingContractExamplesTest` (one JSON per published event), the outbox's own tests under `infrastructure/messaging/outbox` (`OutboxRelayDataJpaTest` against PostgreSQL, `OutboxWiringTest`, `OutboxRabbitPublisherTest`...), `CacheLayerTest` (cache wiring, invalidation and immediate writes, no Redis needed), `DomainModelTest` (domain records), `JpaEntityModelTest` (entities), `DtoValidationTest` (Bean Validation on DTOs), `GlobalExceptionHandlerTest`. Each holds many narrowly-named `@Test` methods (e.g. `stockMovementEntryIncreasesPhysicalAndAvailableBalance`) rather than one test per class — when adding a service/controller/repository/mapper, add a method to the matching layer test instead of creating a new test class; the events of each hook are methods of `BusinessLayerTest` (`RecordingEventPublisher`), and the material lock and the `V11` backfill (the real script, run twice) of `InventoryRepositoryDataJpaTest`.
