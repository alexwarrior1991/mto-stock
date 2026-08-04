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

## API documentation

- OpenAPI JSON: `http://localhost:8080/v3/api-docs`
- Swagger UI: `http://localhost:8080/swagger-ui.html`

## Actuator

The application exposes Actuator endpoints for health, info, metrics and Prometheus:

- Health: `http://localhost:8080/actuator/health`
- Readiness: `http://localhost:8080/actuator/health/readiness`
- Liveness: `http://localhost:8080/actuator/health/liveness`
- Info: `http://localhost:8080/actuator/info`
- Metrics: `http://localhost:8080/actuator/metrics`
- Prometheus: `http://localhost:8080/actuator/prometheus`

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