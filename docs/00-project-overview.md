# Project Overview

Develop a production-ready warehouse and inventory management module for railway catenary construction.

## Stack
- Java 25
- Spring Boot 4
- PostgreSQL
- Spring Data JPA
- Hibernate Envers (change history — see `07-auditing.md`)
- MapStruct
- Flyway
- Lombok
- Maven
- OpenAPI
- Redis (master data cache)
- RabbitMQ (master data events from `mto-configuration`)
- Keycloak (OAuth2 resource server)
- OpenTelemetry (distributed tracing)
- JUnit 5
- Mockito
- Testcontainers

The warehouse stores individual components, not assembled catenary sets.
Assemblies are virtual products defined by a Bill Of Materials (BOM).
