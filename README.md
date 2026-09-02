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
- `SPRING_RABBITMQ_HOST`, `SPRING_RABBITMQ_PORT`, `SPRING_RABBITMQ_USERNAME`, `SPRING_RABBITMQ_PASSWORD`, `SPRING_RABBITMQ_VIRTUAL_HOST`: broker this API consumes master data events from
- `APP_RABBITMQ_ENABLED`: declare the messaging topology and wire the consumer; `false` starts the application without a broker
- `APP_RABBITMQ_MASTER_DATA_LISTENER_ENABLED`: consume from the queue; `false` still declares it, so events accumulate
- `RABBITMQ_DEFAULT_USER`, `RABBITMQ_DEFAULT_PASS`, `RABBITMQ_PORT`, `RABBITMQ_MANAGEMENT_PORT`: the RabbitMQ container of the local Compose stack

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

Create an environment file from the example and update the credentials:

```bash
cp .env.example .env
docker compose up --build
```

The Compose stack starts PostgreSQL, Keycloak, RabbitMQ and the application on a dedicated Docker network, keeps PostgreSQL and RabbitMQ data in the `postgres_data` and `rabbitmq_data` volumes, and waits for every dependency health check before starting the API.

RabbitMQ publishes AMQP on `5672` and its management UI on <http://localhost:15672>. If the broker of `mto-configuration` is already running, comment the `rabbitmq` service out and point `SPRING_RABBITMQ_HOST` at it instead of starting a second one: it is the same exchange and the same queues.

Useful commands:

```bash
docker compose logs -f app
docker compose ps
docker compose down
docker compose down -v
```

## Messaging

This API consumes the master data change events that `mto-configuration` publishes to RabbitMQ. It
binds its own queue `mto.stock.master-data.queue` to the publisher's topic exchange
`mto.master-data.exchange` with the routing key `mto.master-data.#`, and dead-letters what it cannot
process to `mto.stock.master-data.queue.dlq`.

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

`docker compose up` starts a Keycloak on `http://localhost:8082` with the realm already imported and
three development users (`almacen.lector`, `almacen.operario`, `almacen.responsable`), all with
password `local`. Get a token and send it as a bearer token:

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