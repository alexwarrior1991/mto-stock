# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

MTO Stock: a Spring Boot 4 / Java 25 inventory API for a make-to-order warehouse (railway catenary construction). The warehouse stores individual component materials, not assembled catenary sets; assemblies are virtual products defined by a Bill of Materials (BOM) and have no stock of their own.

Read `docs/` in order for full domain/architecture context: `00-project-overview.md`, `01-architecture.md`, `02-domain-model.md`, `03-database.md`, `04-rest-api.md`, `05-development-roadmap.md`. Those documents (plus this file) are the source of truth for the project — `03-database.md` in particular documents the full schema, enums, constraints and indexes and should be checked before changing persistence code.

## Commands

Build / test (use `mvnw.cmd` instead of `./mvnw` on Windows PowerShell):

```bash
./mvnw compile                                    # compile only
./mvnw test                                       # full test suite (needs Docker: many tests use Testcontainers)
./mvnw test -Dtest=BusinessLayerTest               # single test class
./mvnw test -Dtest=BusinessLayerTest#stockMovementEntryIncreasesPhysicalAndAvailableBalance   # single test method
./mvnw spring-boot:run                            # run the app (needs SPRING_PROFILES_ACTIVE + DATABASE_* env vars, see README)
```

Docker Compose stack (Postgres + app):

```bash
cp .env.example .env   # then edit credentials
docker compose up --build
```

- Flyway runs automatically on startup against `src/main/resources/db/migration`; Hibernate is `ddl-auto: validate` in every profile, so schema changes always go through a new Flyway migration, never through entity annotations alone.
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
- Assemblies never have stock — availability is computed on demand from the BOM (`assembly_component`) against current component stock (`BOMCalculationService`).

### Messaging

`mto-stock` consumes the master data change events that `mto-configuration` publishes to RabbitMQ
(`mto.master-data.exchange`, routing key `mto.master-data.#`, own queue `mto.stock.master-data.queue`
with its own DLX/DLQ). The consumer currently **only logs**: no business logic yet.

- `configuration/rabbitmq` — `MasterDataRabbitProperties` (`app.rabbitmq.master-data.*`) and
  `RabbitMqConfiguration` (topology, JSON converter, listener factory). The whole block is gated on
  `app.rabbitmq.enabled`, the consumer additionally on `app.rabbitmq.master-data.listener-enabled`;
  with the first off the application starts without a broker, which is what the tests rely on.
- `infrastructure/messaging/rabbitmq` — contract names/headers and the thin `MasterDataEventConsumer`.
- `application/dto/messaging` + `application/service/MasterDataEventHandler` — the message contract
  and **the extension point where the business logic goes**; `LoggingMasterDataEventHandler` is the
  placeholder implementation.

The message contract is owned by `mto-configuration` — check `docs/06-messaging.md` (and that
repository's `README_MESSAGING.md`) before changing anything under `application/dto/messaging`.

### Testing

- `PostgreSQLTestContainer` (in `support/`) is the shared base for integration tests needing a real Postgres (`postgres:16-alpine` via Testcontainers) with Flyway migrations applied — extend it rather than mocking the datasource for repository/persistence tests.
- Tests are consolidated **one class per layer**, not one class per production class: `BusinessLayerTest` (all services), `RestControllerLayerTest` + `ReservationControllerMockMvcTest` (controllers), `PersistenceLayerTest` + `InventoryRepositoryDataJpaTest` (repositories), `MapperLayerTest` (mappers), `MessagingLayerTest` (RabbitMQ contract, consumer and topology), `DomainModelTest` (domain records), `JpaEntityModelTest` (entities), `DtoValidationTest` (Bean Validation on DTOs), `GlobalExceptionHandlerTest`. Each holds many narrowly-named `@Test` methods (e.g. `stockMovementEntryIncreasesPhysicalAndAvailableBalance`) rather than one test per class — when adding a service/controller/repository/mapper, add a method to the matching layer test instead of creating a new test class.
