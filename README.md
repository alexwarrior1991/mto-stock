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

The Compose stack starts PostgreSQL and the application on a dedicated Docker network, keeps PostgreSQL data in the `postgres_data` volume and waits for the database health check before starting the API.

Useful commands:

```bash
docker compose logs -f app
docker compose ps
docker compose down
docker compose down -v
```

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

Suggested composite realm profiles: `mto-stock-viewer` (`stock-read`), `mto-stock-operator`
(`stock-read`, `stock-write`), `mto-stock-admin` (all four `stock-*`) and `mto-stock-ops`
(`ops-metrics`, `ops-write`).

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

Get a token from Keycloak and send it as a bearer token:

```bash
TOKEN=$(curl -s -X POST \
  "http://auth.mto.local:8082/realms/mto/protocol/openid-connect/token" \
  -d "grant_type=password" \
  -d "client_id=mto-stock-frontend" \
  -d "username=warehouse.operator" \
  -d "password=your_password" | jq -r .access_token)

# 200: reading needs stock-read
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/v1/inventory/materials

# 401: no token at all
curl -i http://localhost:8080/api/v1/inventory/materials

# 403: a stock-read token cannot write
curl -i -X POST -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"code":"MAT-1","name":"Copper wire","unitOfMeasure":"m","minimumStockLevel":10}' \
  http://localhost:8080/api/v1/inventory/materials
```

The Keycloak client that requests the token needs an **audience mapper** adding `mto-stock-api` to
the token's `aud`, otherwise the API rejects it: without that check, a token minted for another
application in the same realm would be accepted here.

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