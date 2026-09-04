# MTO Stock

MTO Stock is a Spring Boot inventory API for make-to-order operations. It manages materials, suppliers, warehouses, projects, stock movements, reservations, virtual assemblies, BOM definitions and availability calculations.

## Requirements

- Java 25
- Maven 3.9+ or the included Maven wrapper
- Docker and Docker Compose for containerized local or production-like execution
- PostgreSQL 16+ when running outside Docker Compose

## Configuration

All runtime configuration is externalized through environment variables. Copy `.env.example` to `.env` for Docker Compose and replace every sample secret before running production workloads.

Main variables:

- `SPRING_PROFILES_ACTIVE`: active Spring profile (`dev`, `test` or `prod`)
- `SERVER_PORT` / `APP_PORT`: container and host HTTP ports
- `APP_VERSION`: version exposed through the Actuator info endpoint
- `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`: application datasource settings
- `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_PORT`: PostgreSQL container settings
- `LOGGING_LEVEL_ROOT`, `LOGGING_LEVEL_APP`, `LOGGING_LEVEL_SQL`: logging verbosity
- `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE`: exposed Actuator endpoints
- `KEYCLOAK_ISSUER_URI`: Keycloak realm that issues the access tokens this API validates
- `KEYCLOAK_CLIENT_ID`: Keycloak client representing this API; its client roles are the permissions the API checks
- `KEYCLOAK_AUDIENCE`, `KEYCLOAK_AUDIENCE_VALIDATION_ENABLED`: audience expected in the token, and whether it is enforced
- `APP_SECURITY_EXPOSE_API_DOCS`: publish Swagger UI and the OpenAPI document without a token
- `APP_CORS_ALLOWED_ORIGIN`: browser origin allowed to call the API
- `KC_BOOTSTRAP_ADMIN_USERNAME`, `KC_BOOTSTRAP_ADMIN_PASSWORD`, `KEYCLOAK_PORT`, `KEYCLOAK_MANAGEMENT_PORT`: the Keycloak container of the local Compose stack
- `SPRING_RABBITMQ_HOST`, `SPRING_RABBITMQ_PORT`, `SPRING_RABBITMQ_USERNAME`, `SPRING_RABBITMQ_PASSWORD`, `SPRING_RABBITMQ_VIRTUAL_HOST`: broker this API consumes master data events from. It must be **the same broker** `mto-configuration` publishes to — the one in `mto-platform`
- `APP_RABBITMQ_ENABLED`: declare the messaging topology and wire the consumer; `false` starts the application without a broker
- `APP_RABBITMQ_MASTER_DATA_LISTENER_ENABLED`: consume from the queue; `false` still declares it, so events accumulate
- `RABBITMQ_DEFAULT_USER`, `RABBITMQ_DEFAULT_PASS`, `RABBITMQ_PORT`, `RABBITMQ_MANAGEMENT_PORT`: the RabbitMQ container of the local Compose stack
- `SPRING_DATA_REDIS_HOST`, `SPRING_DATA_REDIS_PORT`, `SPRING_DATA_REDIS_PASSWORD`, `SPRING_DATA_REDIS_DATABASE`: Redis backing the master data cache
- `APP_CACHE_ENABLED`: cache master data reads in Redis; `false` starts the application without Redis and serves everything from the database
- `APP_CACHE_DEFAULT_TTL`: how long a cached entry lives (default `30m`)
- `APP_CACHE_KEY_PREFIX`: namespace of the cache keys (default `mto-stock:v1:`); **bump the version segment when the shape of a cached response DTO changes**
- `MANAGEMENT_HEALTH_REDIS_ENABLED`: include Redis in `/actuator/health`; off by default, like RabbitMQ, because an unreachable cache does not stop the API from serving
- `REDIS_PASSWORD`, `REDIS_PORT`, `REDIS_MAXMEMORY`: the Redis container of the local Compose stack

## Spring profiles

- `dev`: local development defaults, verbose application logging and detailed health output.
- `test`: test-oriented defaults intended for Testcontainers or explicitly supplied test database variables.
- `prod`: production defaults with graceful shutdown, validated schema, externalized credentials and conservative health details.

## Run locally without Docker

Start PostgreSQL and export the required database variables for the selected profile, then run:

```bash
SPRING_PROFILES_ACTIVE=dev \
DATABASE_URL=jdbc:postgresql://localhost:5432/mto_stock_dev \
DATABASE_USERNAME=your_user \
DATABASE_PASSWORD=your_password \
./mvnw spring-boot:run
```

On Windows PowerShell:

```powershell
$env:SPRING_PROFILES_ACTIVE = "dev"
$env:DATABASE_URL = "jdbc:postgresql://localhost:5432/mto_stock_dev"
$env:DATABASE_USERNAME = "your_user"
$env:DATABASE_PASSWORD = "your_password"
.\mvnw.cmd spring-boot:run
```

## Docker

PostgreSQL, Redis, RabbitMQ, Keycloak and the trace collector live in
[`mto-platform`](https://github.com/alexwarrior1991/mto-platform), the shared local environment for
the whole domain. This repository no longer starts any of them.

That is not tidiness. This service consumes the master data events `mto-configuration` publishes,
and while each repository brought up its own broker, the publisher wrote to one and this consumer
listened on another: **nothing arrived and nothing failed**. One broker is the fix.

The usual way to run it is from the platform, which pulls the published image:

```bash
cd ../mto-platform
cp .env.example .env
docker compose --profile all up -d
./keycloak/apply-partials.sh
```

To run a **local build** of this service against that same infrastructure, `compose.yaml` here
holds only the application:

```bash
cd ../mto-platform && docker compose up -d   # infrastructure only
cd ../mto-stock
cp .env.example .env
docker compose up -d --build
```

It reaches PostgreSQL, Redis and RabbitMQ through `host.docker.internal`, where the platform
publishes them, and Keycloak and the collector through `auth.mto.local` / `otel.mto.local` — the
same names a run from the IDE uses, because the token `iss` is one of them.

Useful commands:

```bash
docker compose logs -f app
docker compose ps
docker compose down
```

## Messaging

This API consumes the master data change events that `mto-configuration` publishes to RabbitMQ. It
binds its own queue `mto.stock.master-data.queue` to the publisher's topic exchange
`mto.master-data.exchange` with the routing key `mto.master-data.#`, and dead-letters what it cannot
process to `mto.stock.master-data.queue.dlq`.

Every message is verified before it is used: `mto-configuration` signs the bytes it sends, and this
service recomputes that signature and rejects to the DLQ anything that does not match. Sharing
`MESSAGING_SIGNATURE_SECRET` between both services makes it an HMAC that protects against tampering;
without it the check is a plain hash that only catches corruption. Set `MESSAGING_SIGNATURE_MODE` to
`REQUIRED` once the secret is shared.

Consumption is idempotent through the **inbox pattern**, the counterpart of the outbox
`mto-configuration` publishes with: every message is recorded in `inbox_message` keyed by its
`operationId`, and a unique constraint in the database — not a check in the code — makes sure the
work runs exactly once however many times the broker delivers it. A duplicate is skipped and
acknowledged; a failure is recorded with its reason and dead-lettered after the configured retries.

Each change is routed by entity type to the `MasterDataEntityHandler` that claims it. Today one is
registered: an execution package keeps a `project` in step with it — created and updated on those
operations, deactivated (never deleted) when the package is removed. The other seven entity types
are logged and ignored. To make the service react to one of them, add a `@Service` implementing
`MasterDataEntityHandler`; the dispatcher picks it up from the context and the consumer is not
touched. Whatever goes there is already covered by the inbox and does not need to check for repeats
itself.

Turn the channel off with `APP_RABBITMQ_ENABLED=false` (no topology, no consumer, no connection: the
application starts without a broker) or keep the topology and stop consuming with
`APP_RABBITMQ_MASTER_DATA_LISTENER_ENABLED=false`. The test suite never needs a broker.

See `docs/06-messaging.md` for the message contract, the full variable list, how the inbox behaves
on duplicates and failures, how to publish a test message from the management UI, and where to add
the business logic.

## Caching

Reads of the five master data entities by id -- material, warehouse, supplier, assembly (with its
BOM inside) and project -- are cached in Redis. Everything else is not, and stock least of all: it
changes with every movement and every reservation, and it already has its own read optimization in
the `inventory_balance` projection.

The cache is optional and off by default (`prod` turns it on). With `app.cache.enabled=false` the
`CacheManager` is a no-op, nobody opens a connection, and the application starts without Redis. A
Redis that falls over while the application is running does not break it either: the failure is
logged and the call goes to the database, which is the whole point of a cache holding data that
lives in PostgreSQL.

Entries are invalidated **after the transaction that changed the row commits**, not when the
writing method returns. Between those two instants a concurrent read would otherwise refill the
cache with the value that is still committed, and leave it there until the TTL expired.

Two things are worth knowing before changing a cached response DTO:

- Each cache serializes exactly one known type, so what Redis holds is plain JSON, readable with
  `redis-cli GET` and with no type marker in it.
- **Adding** a field to one of those records makes entries written by the previous version
  deserialize with `null` in it, and that is served without any error until the TTL expires. Bump
  the version segment of `APP_CACHE_KEY_PREFIX` in the same deployment: the old entries are then
  orphaned and expire on their own, instead of having to flush Redis by hand.

## Database migrations

Flyway is enabled by default in every profile and runs automatically during application startup. Migration scripts live in `src/main/resources/db/migration` and are validated by Hibernate with `ddl-auto=validate`.

## Security

The API is an OAuth2 **resource server**. It does not issue tokens and stores no users: Keycloak
authenticates people and issues the JWT, and the API only validates it (signature against the realm
JWK Set, issuer, expiry and audience) and maps its roles to authorities. Sessions are stateless and
CSRF is disabled, because every request carries its own `Authorization` header.

### Roles

Permissions are **client roles** of the `mto-stock-api` Keycloak client, written in lower case with
hyphens (`stock-read`); the API normalises them to `STOCK_READ`. Business profiles are **composite
realm roles** that group them, so what a profile can do changes in Keycloak without a deployment.

| Client role | Grants |
|---|---|
| `stock-read` | Read the catalogue, stock levels, movement history and reservations |
| `stock-write` | Create and update catalogue entries; register entries, outputs, transfers and reservations |
| `stock-delete` | Cancel reservations (`DELETE`) |
| `stock-adjust` | Register inventory adjustments — required **in addition to** `stock-write` |
| `ops-metrics` | Read the Actuator endpoints beyond health and info |
| `ops-write` | Actuator operations that change state (`POST`, `DELETE`) |

`stock-adjust` is separate because an adjustment is the only write that corrects the balance with no
source document behind it: without its own permission, anyone who could register an output could
also make it disappear from the balance.

Business profiles are composite realm roles that group them:

| Realm profile | Groups |
|---|---|
| `mto-warehouse-viewer` | `stock-read` |
| `mto-warehouse-operator` | `stock-read`, `stock-write` |
| `mto-warehouse-admin` | the operator ones plus `stock-delete` and `stock-adjust` |

The realm is shared with `mto-configuration` — same users, same issuer — and its definition is
versioned in [`keycloak/`](keycloak/), which also documents how to load it, what the two import
files are for, and the one cross-repo step (`mto-frontend` needs an audience mapper for
`mto-stock-api`). `KeycloakAuthorizationIT` checks the whole thing against a real Keycloak.

### Protected and public endpoints

| Endpoint | Requirement |
|---|---|
| `GET`/`HEAD /api/v1/inventory/**` | `stock-read` |
| `POST`/`PUT`/`PATCH /api/v1/inventory/**` | `stock-write` |
| `POST /api/v1/inventory/movements/adjustments` | `stock-write` **and** `stock-adjust` |
| `DELETE /api/v1/inventory/**` | `stock-delete` |
| `GET /actuator/**` | `ops-metrics` |
| `POST`/`DELETE /actuator/**` | `ops-write` |
| `/actuator/health`, `/actuator/health/**`, `/actuator/info` | public |
| `/v3/api-docs/**`, `/swagger-ui/**`, `/swagger-ui.html` | public only when `APP_SECURITY_EXPOSE_API_DOCS=true` |

Rejections use the same `ApiErrorResponse` payload as any other API error: `401` with error code
`AUTH-401` when the token is missing or invalid, `403` with `AUTH-403` when the role is not enough.

### Authenticating

`mto-platform` starts a Keycloak on `http://localhost:8082`. Its `apply-partials.sh` applies this
repository's `keycloak/mto-stock-partial-import.json` (the `mto-stock-api` client, its permissions
and the `mto-warehouse-*` profiles) and `keycloak/mto-stock-dev.json` (the three development users
`almacen.lector`, `almacen.operario` and `almacen.responsable`, all with password `local`). Get a
token and send it as a bearer token:

```bash
TOKEN=$(curl -s -X POST \
  "http://auth.mto.local:8082/realms/mto/protocol/openid-connect/token" \
  -d "grant_type=password" \
  -d "client_id=mto-frontend" \
  -d "username=almacen.operario" \
  -d "password=local" | jq -r .access_token)

# 200: reading needs stock-read
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/v1/inventory/materials

# 401: no token at all
curl -i http://localhost:8080/api/v1/inventory/materials

# 403: use a token for almacen.lector instead - reading does not grant writing
curl -i -X POST -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"code":"MAT-1","name":"Copper wire","unitOfMeasure":"m","minimumStockLevel":10}' \
  http://localhost:8080/api/v1/inventory/materials
```

The Keycloak client that requests the token needs an **audience mapper** adding `mto-stock-api` to
the token's `aud`, otherwise the API rejects it: without that check, a token minted for another
application in the same realm would be accepted here. See [`keycloak/README.md`](keycloak/README.md)
— a missing mapper is the failure that looks intermittent, because Keycloak supplies the audience on
its own only for users who hold roles in the client.

The authenticated username also becomes the author recorded in the JPA audit columns.

## API documentation

- OpenAPI JSON: `http://localhost:8080/v3/api-docs`
- Swagger UI: `http://localhost:8080/swagger-ui.html`

Both are public only while `APP_SECURITY_EXPOSE_API_DOCS=true` (the default in `dev` and `test`, off
in `prod`). Use the *Authorize* button in Swagger UI to send the bearer token with each try-out.

## Actuator

The application exposes Actuator endpoints for health, info, metrics and Prometheus:

- Health: `http://localhost:8080/actuator/health`
- Readiness: `http://localhost:8080/actuator/health/readiness`
- Liveness: `http://localhost:8080/actuator/health/liveness`
- Info: `http://localhost:8080/actuator/info`
- Metrics: `http://localhost:8080/actuator/metrics`
- Prometheus: `http://localhost:8080/actuator/prometheus`

Health and info are public so orchestrators can probe them without a token. The rest requires
`ops-metrics`, and anything that modifies state requires `ops-write`.

## Distributed tracing

`mto-stock` exports traces over **OTLP** using `spring-boot-starter-opentelemetry` — the Boot 4
replacement for the `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp` pair. The same
setup is in `mto-configuration` and `mto-gateway`, so a request can be followed end to end:

```
mto-gateway  →  mto-configuration  →  (RabbitMQ)  →  mto-stock
```

This service is the last link, and it is the one that used to break the chain: without the starter a
trace reached its edge and stopped there.

**The queue hop is traced too.** `mto-configuration` writes `traceparent`/`tracestate` into the AMQP
headers when it publishes from its outbox, and this side continues that trace instead of starting a
new one, via `spring.rabbitmq.listener.simple.observation-enabled`. That property only works because
the listener factory goes through `SimpleRabbitListenerContainerFactoryConfigurer` — a hand-rolled
factory that skips the configurer silently discards the whole `spring.rabbitmq.listener.simple.*`
block, this line included (see `RabbitMqConfiguration`).

| Variable | Default | What it does |
|---|---|---|
| `MTO_TRACING_ENABLED` | `true` | Turns tracing on and off |
| `MTO_TRACING_SAMPLING_PROBABILITY` | `0.1` | Fraction of traces recorded |
| `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | `http://localhost:4318/v1/traces` | Collector, HTTP OTLP |
| `SPRING_RABBITMQ_LISTENER_OBSERVATION_ENABLED` | `true` | Continue the trace across the queue |

Sampling at 10% does **not** break the chain between services: an unsampled span still propagates its
`traceparent` with the `00` flag. Sampling everything in a service with real traffic is expensive and
is almost never needed.

The collector is a **Jaeger** all-in-one, reachable at http://localhost:16686. Each stack ships its
own so it can be brought up alone — exactly like each one ships its own Keycloak — and they collide
on the same host ports when several are up, just as Postgres, RabbitMQ and Keycloak already do.

That collision is what makes it work. In Docker this service exports to `otel.mto.local:4318`,
resolved through the host with `extra_hosts` (the same idiom already used for Keycloak via
`auth.mto.local`), so whichever Jaeger holds the port receives **all** the spans and a trace that
starts at the gateway and ends here is visible whole in one place. A collector reachable only inside
each stack would leave every distributed trace split across viewers.

OTLP **metrics** export stays off (`management.otlp.metrics.export.enabled: false`): the starter also
drags in an OTLP metrics registry, and without that line the service would push metrics to a
collector on top of exposing them at `/actuator/prometheus`.

## Running tests

Run the full test suite with:

```bash
./mvnw test
```

On Windows PowerShell:

```powershell
.\mvnw.cmd test
```

Some persistence tests use Testcontainers, so Docker must be available when executing the full suite.

`KeycloakAuthorizationIT` runs the authorization rules against a real Keycloak and is executed by
Failsafe during `./mvnw verify`. Without Docker it is skipped rather than failed:

```bash
./mvnw verify -Dit.test=KeycloakAuthorizationIT -Dtest=NONE \
  -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false
```