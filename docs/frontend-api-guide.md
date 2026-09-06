# Frontend API Guide

This guide documents the current Inventory API contract for frontend development and API testing. It is based on the Spring REST controllers exposed under `/api/v1/inventory`.

## Base URL

- Local default: `http://localhost:8080`
- Docker Compose default: `http://localhost:${APP_PORT:-8080}`
- API root: `{{baseUrl}}/api/v1/inventory`

## API version

- Current version: `v1`
- Versioned path segment: `/api/v1`
- Inventory API prefix: `/api/v1/inventory`

## Authentication

- Current status: every endpoint under `/api/v1/inventory` requires a Keycloak-issued JWT. The API is an OAuth2 resource server: it validates tokens and never issues them, so there is no login endpoint to call here.
- Current client behavior: obtain the token from Keycloak and add `Authorization: Bearer <jwt>` to every request through an API client interceptor/middleware. Keep login and token refresh isolated from the inventory service modules.
- Public endpoints: `/actuator/health`, `/actuator/health/**` and `/actuator/info`; `/v3/api-docs/**` and `/swagger-ui/**` only where the deployment sets `APP_SECURITY_EXPOSE_API_DOCS=true`.
- Authorization: reads need the `stock-read` client role, writes need `stock-write`, cancelling a reservation needs `stock-delete`, and registering an inventory adjustment needs `stock-adjust` on top of `stock-write`. The token's `aud` must contain `mto-stock-api`.

## Request and response conventions

- Payload format: JSON.
- Timestamp format: ISO-8601 instant strings, for example `2026-08-04T10:46:00Z`.
- Identifiers: UUID strings.
- Decimal quantities: numeric JSON values with up to 13 integer digits and up to 6 fraction digits where quantities are accepted.
- Create endpoints return `201 Created` and a `Location` header when a single resource is created.
- Update/read/search endpoints return `200 OK`.
- Reservation cancellation uses `DELETE` but returns the cancelled reservation body with `200 OK`.

## Pagination

Collection endpoints use Spring pageable query parameters:

- `page`: zero-based page number, default `0`.
- `size`: page size, default `20`.
- `sort`: one or more sort expressions, such as `sort=code,asc` or `sort=createdAt,desc`.

Paginated response shape:

```json
{
  "content": [],
  "page": {
    "number": 0,
    "size": 20,
    "totalElements": 0,
    "totalPages": 0,
    "first": true,
    "last": true
  }
}
```

## Sorting

- Use `sort=<field>,asc` or `sort=<field>,desc`.
- Repeat `sort` for compound sorting: `sort=active,desc&sort=code,asc`.
- Typical sortable fields are `code`, `name`, `createdAt`, `updatedAt`, and domain-specific timestamp fields such as `occurredAt`.

## Filtering

- Filters are query parameters and are optional unless explicitly marked required.
- Boolean values are `true` or `false`.
- UUID filters must be valid UUID strings.
- Date filters use ISO-8601 instant strings.
- Empty or malformed parameters return `400 Bad Request`.

## Standard error format

All API errors use this shape:

```json
{
  "timestamp": "2026-08-04T10:46:00Z",
  "status": 400,
  "error": "BAD_REQUEST",
  "message": "Request validation failed",
  "path": "/api/v1/inventory/materials",
  "method": "POST",
  "errorCode": "REQ-VALIDATION",
  "correlationId": "corr-20260804-0001",
  "validationErrors": [
    {
      "field": "code",
      "message": "must not be blank"
    }
  ]
}
```

## HTTP status codes

- `200 OK`: request succeeded.
- `201 Created`: resource or movement created.
- `400 Bad Request`: malformed query parameter, invalid JSON, or validation failure.
- `401 Unauthorized`: missing, expired or otherwise invalid bearer token. Error code `AUTH-401`; the `WWW-Authenticate` header says whether the token has to be refreshed or the user re-authenticated.
- `403 Forbidden`: the token is valid but the user lacks the role the operation requires. Error code `AUTH-403`.
- `404 Not Found`: referenced resource was not found.
- `409 Conflict`: duplicate business key or insufficient stock.
- `422 Unprocessable Entity`: domain rule violation, such as invalid BOM or reservation lifecycle rule.
- `500 Internal Server Error`: unexpected server-side failure.

## Common headers

- Request: `Accept: application/json`
- Request with body: `Content-Type: application/json`
- Optional tracing: `X-Correlation-Id: <client-generated-id>` if supported by the deployment.
- Auth: `Authorization: Bearer <jwt>` on every request to `/api/v1/inventory`.

## Content-Type

- Request bodies: `application/json`.
- Response bodies: `application/json`.

## Validation rules

- `code`: required for create/update master data; max length `64`; must be unique per resource type.
- `name`: required for create/update master data; max length `255`.
- `active`: optional/required depending on resource update DTO; use boolean values.
- `unitOfMeasure`: required for materials; max length `32`.
- `minimumStockLevel`: required for materials; zero or positive; max precision 13 integer digits and 6 decimals.
- `quantity`: required for BOM, stock movements, and reservations; positive; max precision 13 integer digits and 6 decimals.
- `externalReference`: optional stock movement external document/reference; max length `128`.
- `occurredAt` and `reservedAt`: optional ISO-8601 instant strings; server defaults may apply when omitted.
- Transfer `sourceWarehouseId` and `targetWarehouseId` must be different.
- Assembly `components` must contain at least one BOM line.
- Reservation terminal actions only apply to valid lifecycle states.

## Shared example entities

The examples below reuse these IDs:

```json
{
  "materialId": "018f60be-1b9a-7cc3-8c6b-2f93e8c6a020",
  "secondMaterialId": "018f60be-1b9a-7cc3-8c6b-2f93e8c6a021",
  "warehouseId": "018f60be-1b9a-7cc3-8c6b-2f93e8c6a001",
  "targetWarehouseId": "018f60be-1b9a-7cc3-8c6b-2f93e8c6a002",
  "projectId": "018f60be-1b9a-7cc3-8c6b-2f93e8c6a010",
  "supplierId": "018f60be-1b9a-7cc3-8c6b-2f93e8c6a030",
  "assemblyId": "018f60be-1b9a-7cc3-8c6b-2f93e8c6a040",
  "movementId": "018f60be-1b9a-7cc3-8c6b-2f93e8c6a050",
  "reservationId": "018f60be-1b9a-7cc3-8c6b-2f93e8c6a060"
}
```

### Revision response

Every `/revisions` endpoint returns the paginated envelope described in [Pagination](#pagination),
with this entry shape. `entity` is the same resource payload the normal `GET` returns, as it stood at
that revision, so there is no second shape to learn:

```json
{
  "revision": {
    "revision": 42,
    "revisionAt": "2026-09-01T10:15:30Z",
    "operation": "UPDATED",
    "author": "warehouse.operator",
    "source": "HTTP",
    "correlationId": "corr-20260901-0001"
  },
  "entity": {
    "id": "018f60be-1b9a-7cc3-8c6b-2f93e8c6a020",
    "code": "MAT-COPPER-50",
    "name": "Copper contact wire 150 mm2",
    "unitOfMeasure": "M",
    "minimumStockLevel": 250.000000,
    "active": true,
    "audit": null
  }
}
```

Three things worth knowing before you render this:

- `operation` is `CREATED`, `UPDATED` or `DELETED`. On a `DELETED` entry `entity` still carries the
  last known values, not nulls — that is what makes a removed BOM line readable.
- The resource's own `audit` block comes back empty on purpose. The who and when of each revision
  live in `revision`; repeating them per row would be the same fact twice.
- `author` may be `system` (an internal process wrote it) or `unknown` (the identity was lost on the
  way). The two are recorded separately on purpose, so do not collapse them in the UI.
- `source` says where the write came from — `HTTP`, `MESSAGING`, `SYSTEM` or `BASELINE` — and
  therefore which identifier space `correlationId` belongs to. `BASELINE` marks the snapshot taken
  when change history was first switched on; it is not a creation event.

## Materials

### Create material

- URL: `/api/v1/inventory/materials`
- Method: `POST`
- Description: Creates a catalogue material. Material codes must be unique.
- Request example:

```http
POST /api/v1/inventory/materials
Content-Type: application/json
Accept: application/json

{
  "code": "MAT-COPPER-50",
  "name": "Copper catenary wire 50 mm2",
  "unitOfMeasure": "m",
  "minimumStockLevel": 250.000000
}
```

- Response example:

```json
{
  "id": "018f60be-1b9a-7cc3-8c6b-2f93e8c6a020",
  "code": "MAT-COPPER-50",
  "name": "Copper catenary wire 50 mm2",
  "unitOfMeasure": "m",
  "minimumStockLevel": 250.000000,
  "active": true,
  "audit": {
    "createdAt": "2026-08-04T10:46:00Z",
    "updatedAt": "2026-08-04T10:46:00Z",
    "createdBy": "system",
    "updatedBy": "system"
  }
}
```

- Possible errors: `400`, `409`, `500`.

### Update material

- URL: `/api/v1/inventory/materials/{id}`
- Method: `PUT`
- Description: Replaces editable material fields.
- Request example: same body as create, optionally including `active` when supported by the current DTO.
- Response example: material response.
- Possible errors: `400`, `404`, `409`, `500`.

### Get material

- URL: `/api/v1/inventory/materials/{id}`
- Method: `GET`
- Description: Returns one material by UUID.
- Request example: `GET /api/v1/inventory/materials/018f60be-1b9a-7cc3-8c6b-2f93e8c6a020`
- Response example: material response.
- Possible errors: `404`, `500`.

### Search materials

- URL: `/api/v1/inventory/materials?code=&name=&active=&warehouseId=&belowMinimum=&page=&size=&sort=`
- Method: `GET`
- Description: Searches materials by code, name, active state, warehouse and minimum-stock condition.
- Request example: `GET /api/v1/inventory/materials?name=Copper&active=true&page=0&size=20&sort=code,asc`
- Response example: paginated material response.
- Possible errors: `400`, `500`.

### Search low-stock materials

- URL: `/api/v1/inventory/materials/low-stock?warehouseId=&page=&size=&sort=`
- Method: `GET`
- Description: Returns active materials below configured minimum stock.
- Request example: `GET /api/v1/inventory/materials/low-stock?warehouseId=018f60be-1b9a-7cc3-8c6b-2f93e8c6a001`
- Response example: paginated material response.
- Possible errors: `400`, `500`.

### Get material stock

- URL: `/api/v1/inventory/materials/{id}/stock?warehouseId=`
- Method: `GET`
- Description: Calculates physical, reserved and available stock for one material globally or by warehouse.
- Request example: `GET /api/v1/inventory/materials/018f60be-1b9a-7cc3-8c6b-2f93e8c6a020/stock?warehouseId=018f60be-1b9a-7cc3-8c6b-2f93e8c6a001`
- Response example:

```json
{
  "materialId": "018f60be-1b9a-7cc3-8c6b-2f93e8c6a020",
  "warehouseId": "018f60be-1b9a-7cc3-8c6b-2f93e8c6a001",
  "physicalStock": 500.000000,
  "reservedStock": 60.000000,
  "availableStock": 440.000000
}
```

- Possible errors: `404`, `500`.

### Get material movement history

- URL: `/api/v1/inventory/materials/{id}/movements?warehouseId=&dateFrom=&dateTo=&user=&page=&size=&sort=`
- Method: `GET`
- Description: Returns movement history for one material.
- Request example: `GET /api/v1/inventory/materials/018f60be-1b9a-7cc3-8c6b-2f93e8c6a020/movements?dateFrom=2026-08-01T00:00:00Z&dateTo=2026-08-31T23:59:59Z`
- Response example: paginated stock movement response.
- Possible errors: `400`, `500`.

### Get material change history

- URL: `/api/v1/inventory/materials/{id}/revisions?page=&size=`
- Method: `GET`
- Description: Returns the audited change history of one material, newest revision first. Not the
  same thing as the movements endpoint above: that one is the stock ledger (what came in and out),
  this one is who changed the record — the minimum stock level, the unit, the active flag — and what
  it said before.
- Request example: `GET /api/v1/inventory/materials/018f60be-1b9a-7cc3-8c6b-2f93e8c6a020/revisions?page=0&size=20`
- Response example: paginated revision response.
- Possible errors: `404`, `500`.

## Warehouses

### Create warehouse

- URL: `/api/v1/inventory/warehouses`
- Method: `POST`
- Description: Creates a warehouse.
- Request example:

```json
{
  "code": "WH-MAD-01",
  "name": "Madrid central warehouse",
  "active": true
}
```

- Response example: warehouse response with `id`, `code`, `name`, `active`, and `audit`.
- Possible errors: `400`, `409`, `500`.

### Update warehouse

- URL: `/api/v1/inventory/warehouses/{id}`
- Method: `PUT`
- Description: Updates a warehouse catalogue record.
- Request example: warehouse create body.
- Response example: warehouse response.
- Possible errors: `400`, `404`, `500`.

### Get warehouse

- URL: `/api/v1/inventory/warehouses/{id}`
- Method: `GET`
- Description: Returns one warehouse by UUID.
- Request example: `GET /api/v1/inventory/warehouses/018f60be-1b9a-7cc3-8c6b-2f93e8c6a001`
- Response example: warehouse response.
- Possible errors: `404`, `500`.

### List warehouses

- URL: `/api/v1/inventory/warehouses?page=&size=&sort=`
- Method: `GET`
- Description: Returns a pageable list of warehouses.
- Request example: `GET /api/v1/inventory/warehouses?page=0&size=20&sort=code,asc`
- Response example: paginated warehouse response.
- Possible errors: `500`.

### Get warehouse material stock

- URL: `/api/v1/inventory/warehouses/{id}/inventory?materialId=`
- Method: `GET`
- Description: Calculates stock for one material in one warehouse.
- Request example: `GET /api/v1/inventory/warehouses/018f60be-1b9a-7cc3-8c6b-2f93e8c6a001/inventory?materialId=018f60be-1b9a-7cc3-8c6b-2f93e8c6a020`
- Response example: material stock response.
- Possible errors: `404`, `500`.

### Transfer stock from warehouse module

- URL: `/api/v1/inventory/warehouses/transfers`
- Method: `POST`
- Description: Alias that creates outgoing and incoming transfer movements.
- Request example: stock transfer body documented under Stock Movements.
- Response example: array of stock movement responses.
- Possible errors: `400`, `409`, `422`, `500`.

### Get warehouse change history

- URL: `/api/v1/inventory/warehouses/{id}/revisions?page=&size=`
- Method: `GET`
- Description: Returns the audited change history of one warehouse, newest revision first.
- Request example: `GET /api/v1/inventory/warehouses/018f60be-1b9a-7cc3-8c6b-2f93e8c6a001/revisions?page=0&size=20`
- Response example: paginated revision response.
- Possible errors: `404`, `500`.

## Stock Movements

### Register stock entry

- URL: `/api/v1/inventory/movements/entries`
- Method: `POST`
- Description: Adds physical stock, optionally linked to a supplier.
- Request example:

```json
{
  "materialId": "018f60be-1b9a-7cc3-8c6b-2f93e8c6a020",
  "warehouseId": "018f60be-1b9a-7cc3-8c6b-2f93e8c6a001",
  "supplierId": "018f60be-1b9a-7cc3-8c6b-2f93e8c6a030",
  "quantity": 500.000000,
  "occurredAt": "2026-08-04T10:46:00Z",
  "externalReference": "DEL-2026-00045",
  "notes": "Supplier delivery for phase 1"
}
```

- Response example: stock movement response.
- Possible errors: `400`, `422`, `500`.

### Register stock output

- URL: `/api/v1/inventory/movements/outputs`
- Method: `POST`
- Description: Removes physical stock, optionally linked to a project or reservation.
- Request example:

```json
{
  "materialId": "018f60be-1b9a-7cc3-8c6b-2f93e8c6a020",
  "warehouseId": "018f60be-1b9a-7cc3-8c6b-2f93e8c6a001",
  "projectId": "018f60be-1b9a-7cc3-8c6b-2f93e8c6a010",
  "reservationId": null,
  "quantity": 120.000000,
  "occurredAt": "2026-08-04T11:00:00Z",
  "externalReference": "OUT-2026-00018",
  "notes": "Issued to installation crew"
}
```

- Response example: stock movement response.
- Possible errors: `400`, `409`, `422`, `500`.

### Register stock adjustment

- URL: `/api/v1/inventory/movements/adjustments`
- Method: `POST`
- Description: Creates a positive or negative inventory correction.
- Request example uses `direction`: `POSITIVE` or `NEGATIVE`.
- Response example: stock movement response.
- Possible errors: `400`, `409`, `422`, `500`.

### Transfer stock

- URL: `/api/v1/inventory/movements/transfers`
- Method: `POST`
- Description: Atomically creates outgoing and incoming transfer movements.
- Request example:

```json
{
  "materialId": "018f60be-1b9a-7cc3-8c6b-2f93e8c6a020",
  "sourceWarehouseId": "018f60be-1b9a-7cc3-8c6b-2f93e8c6a001",
  "targetWarehouseId": "018f60be-1b9a-7cc3-8c6b-2f93e8c6a002",
  "quantity": 80.000000,
  "occurredAt": "2026-08-04T12:00:00Z",
  "externalReference": "TRF-2026-00011",
  "notes": "Rebalance stock for project start"
}
```

- Response example: array of stock movement responses.
- Possible errors: `400`, `409`, `422`, `500`.

### Get stock movement

- URL: `/api/v1/inventory/movements/{id}`
- Method: `GET`
- Description: Returns one append-only stock movement by UUID.
- Request example: `GET /api/v1/inventory/movements/018f60be-1b9a-7cc3-8c6b-2f93e8c6a050`
- Response example: stock movement response.
- Possible errors: `404`, `500`.

### Search stock movements

- URL: `/api/v1/inventory/movements?movementType=&warehouseId=&projectId=&materialId=&dateFrom=&dateTo=&user=&page=&size=&sort=`
- Method: `GET`
- Description: Searches movement history. `movementType` values are `ENTRY`, `OUTPUT`, `POSITIVE_ADJUSTMENT`, `NEGATIVE_ADJUSTMENT`, `INCOMING_TRANSFER`, and `OUTGOING_TRANSFER`.
- Request example: `GET /api/v1/inventory/movements?movementType=ENTRY&warehouseId=018f60be-1b9a-7cc3-8c6b-2f93e8c6a001&page=0&size=20&sort=occurredAt,desc`
- Response example: paginated stock movement response.
- Possible errors: `400`, `500`.

## Assemblies

### Create assembly

- URL: `/api/v1/inventory/assemblies`
- Method: `POST`
- Description: Creates a virtual assembly with its bill of materials.
- Request example:

```json
{
  "code": "ASM-BRACKET-001",
  "name": "Catenary support bracket kit",
  "components": [
    {
      "materialId": "018f60be-1b9a-7cc3-8c6b-2f93e8c6a020",
      "quantity": 2.000000
    },
    {
      "materialId": "018f60be-1b9a-7cc3-8c6b-2f93e8c6a021",
      "quantity": 4.000000
    }
  ]
}
```

- Response example: assembly response including `components`.
- Possible errors: `400`, `409`, `422`, `500`.

### Update assembly

- URL: `/api/v1/inventory/assemblies/{id}`
- Method: `PUT`
- Description: Updates an assembly and replaces its BOM definition.
- Request example: assembly create body, plus `active` if supported by the update DTO.
- Response example: assembly response.
- Possible errors: `400`, `404`, `409`, `422`, `500`.

### Get assembly

- URL: `/api/v1/inventory/assemblies/{id}`
- Method: `GET`
- Description: Returns one assembly and its BOM.
- Request example: `GET /api/v1/inventory/assemblies/018f60be-1b9a-7cc3-8c6b-2f93e8c6a040`
- Response example: assembly response.
- Possible errors: `404`, `500`.

### Search assemblies

- URL: `/api/v1/inventory/assemblies?code=&name=&active=&page=&size=&sort=`
- Method: `GET`
- Description: Searches assemblies by code, name, and active state.
- Request example: `GET /api/v1/inventory/assemblies?name=Bracket&active=true&page=0&size=20&sort=code,asc`
- Response example: paginated assembly response.
- Possible errors: `400`, `500`.

### Calculate assembly availability

- URL: `/api/v1/inventory/assemblies/{id}/availability?warehouseId=`
- Method: `GET`
- Description: Calculates maximum producible quantity and limiting/missing components.
- Request example: `GET /api/v1/inventory/assemblies/018f60be-1b9a-7cc3-8c6b-2f93e8c6a040/availability?warehouseId=018f60be-1b9a-7cc3-8c6b-2f93e8c6a001`
- Response example: assembly availability response.
- Possible errors: `404`, `422`, `500`.

### Calculate production capacity

- URL: `/api/v1/inventory/assemblies/{id}/production-capacity?warehouseId=`
- Method: `GET`
- Description: Alias for assembly availability using ERP production-capacity terminology.
- Request example: `GET /api/v1/inventory/assemblies/018f60be-1b9a-7cc3-8c6b-2f93e8c6a040/production-capacity?warehouseId=018f60be-1b9a-7cc3-8c6b-2f93e8c6a001`
- Response example: assembly availability response.
- Possible errors: `404`, `422`, `500`.

### Get assembly change history

- URL: `/api/v1/inventory/assemblies/{id}/revisions?page=&size=`
- Method: `GET`
- Description: Returns the audited change history of one assembly, newest revision first. Editing the
  bill of materials also produces a revision of the assembly itself, even when its own fields did not
  change — what the assembly is made of is part of what it is.
- Request example: `GET /api/v1/inventory/assemblies/018f60be-1b9a-7cc3-8c6b-2f93e8c6a040/revisions?page=0&size=20`
- Response example: paginated revision response.
- Possible errors: `404`, `500`.

## Reservations

### Create reservation

- URL: `/api/v1/inventory/reservations`
- Method: `POST`
- Description: Reserves available stock without changing physical stock.
- Request example:

```json
{
  "materialId": "018f60be-1b9a-7cc3-8c6b-2f93e8c6a020",
  "warehouseId": "018f60be-1b9a-7cc3-8c6b-2f93e8c6a001",
  "projectId": "018f60be-1b9a-7cc3-8c6b-2f93e8c6a010",
  "quantity": 60.000000,
  "reservedAt": "2026-08-04T10:46:00Z"
}
```

- Response example: reservation response with status `ACTIVE`.
- Possible errors: `400`, `409`, `422`, `500`.

### Update reservation

- URL: `/api/v1/inventory/reservations/{id}`
- Method: `PUT`
- Description: Updates editable fields of an active reservation.
- Request example:

```json
{
  "warehouseId": "018f60be-1b9a-7cc3-8c6b-2f93e8c6a001",
  "projectId": "018f60be-1b9a-7cc3-8c6b-2f93e8c6a010",
  "quantity": 45.000000
}
```

- Response example: reservation response.
- Possible errors: `400`, `404`, `409`, `422`, `500`.

### Cancel reservation

- URL: `/api/v1/inventory/reservations/{id}`
- Method: `DELETE`
- Description: Cancels a reservation and releases available-stock hold.
- Request example: `DELETE /api/v1/inventory/reservations/018f60be-1b9a-7cc3-8c6b-2f93e8c6a060`
- Response example: reservation response with status `CANCELLED`.
- Possible errors: `404`, `422`, `500`.

### Release reservation

- URL: `/api/v1/inventory/reservations/{id}/release`
- Method: `POST`
- Description: Releases reserved quantity without consuming physical stock.
- Request example: `POST /api/v1/inventory/reservations/018f60be-1b9a-7cc3-8c6b-2f93e8c6a060/release`
- Response example: reservation response with status `RELEASED`.
- Possible errors: `404`, `422`, `500`.

### Consume reservation

- URL: `/api/v1/inventory/reservations/{id}/consume`
- Method: `POST`
- Description: Consumes an active reservation through the business layer.
- Request example: `POST /api/v1/inventory/reservations/018f60be-1b9a-7cc3-8c6b-2f93e8c6a060/consume`
- Response example: reservation response with released/consumed lifecycle state as implemented by the backend.
- Possible errors: `404`, `409`, `422`, `500`.

### Get reservation

- URL: `/api/v1/inventory/reservations/{id}`
- Method: `GET`
- Description: Returns one reservation by UUID.
- Request example: `GET /api/v1/inventory/reservations/018f60be-1b9a-7cc3-8c6b-2f93e8c6a060`
- Response example: reservation response.
- Possible errors: `404`, `500`.

### Search reservations

- URL: `/api/v1/inventory/reservations?warehouseId=&status=&projectId=&materialId=&page=&size=&sort=`
- Method: `GET`
- Description: Searches reservations by lifecycle and ownership filters.
- Request example: `GET /api/v1/inventory/reservations?status=ACTIVE&projectId=018f60be-1b9a-7cc3-8c6b-2f93e8c6a010&page=0&size=20`
- Response example: paginated reservation response.
- Possible errors: `400`, `500`.

### Get reservation change history

- URL: `/api/v1/inventory/reservations/{id}/revisions?page=&size=`
- Method: `GET`
- Description: Returns the audited change history of one reservation, newest revision first: every
  status transition and every quantity change, not just the state it ended in.
- Request example: `GET /api/v1/inventory/reservations/018f60be-1b9a-7cc3-8c6b-2f93e8c6a060/revisions?page=0&size=20`
- Response example: paginated revision response.
- Possible errors: `404`, `500`.

## Suppliers

### Create supplier

- URL: `/api/v1/inventory/suppliers`
- Method: `POST`
- Description: Creates a supplier catalogue record.
- Request example:

```json
{
  "code": "SUP-CAT-001",
  "name": "Catenary Components Europe",
  "active": true
}
```

- Response example: supplier response with `id`, `code`, `name`, `active`, and `audit`.
- Possible errors: `400`, `409`, `500`.

### Update supplier

- URL: `/api/v1/inventory/suppliers/{id}`
- Method: `PUT`
- Description: Updates a supplier catalogue record.
- Request example: supplier create body.
- Response example: supplier response.
- Possible errors: `400`, `404`, `500`.

### Get supplier

- URL: `/api/v1/inventory/suppliers/{id}`
- Method: `GET`
- Description: Returns one supplier by UUID.
- Request example: `GET /api/v1/inventory/suppliers/018f60be-1b9a-7cc3-8c6b-2f93e8c6a030`
- Response example: supplier response.
- Possible errors: `404`, `500`.

### List suppliers

- URL: `/api/v1/inventory/suppliers?page=&size=&sort=`
- Method: `GET`
- Description: Returns a pageable supplier list.
- Request example: `GET /api/v1/inventory/suppliers?page=0&size=20&sort=code,asc`
- Response example: paginated supplier response.
- Possible errors: `500`.

### Get supplier change history

- URL: `/api/v1/inventory/suppliers/{id}/revisions?page=&size=`
- Method: `GET`
- Description: Returns the audited change history of one supplier, newest revision first.
- Request example: `GET /api/v1/inventory/suppliers/018f60be-1b9a-7cc3-8c6b-2f93e8c6a030/revisions?page=0&size=20`
- Response example: paginated revision response.
- Possible errors: `404`, `500`.

## Projects

### Create project

- URL: `/api/v1/inventory/projects`
- Method: `POST`
- Description: Creates a project catalogue record.
- Request example:

```json
{
  "code": "PRJ-AVE-2026-001",
  "name": "High-speed catenary renewal section A",
  "active": true
}
```

- Response example: project response with `id`, `code`, `name`, `active`, and `audit`.
- Possible errors: `400`, `409`, `500`.

### Update project

- URL: `/api/v1/inventory/projects/{id}`
- Method: `PUT`
- Description: Updates a project catalogue record.
- Request example: project create body.
- Response example: project response.
- Possible errors: `400`, `404`, `500`.

### Get project

- URL: `/api/v1/inventory/projects/{id}`
- Method: `GET`
- Description: Returns one project by UUID.
- Request example: `GET /api/v1/inventory/projects/018f60be-1b9a-7cc3-8c6b-2f93e8c6a010`
- Response example: project response.
- Possible errors: `404`, `500`.

### List projects

- URL: `/api/v1/inventory/projects?page=&size=&sort=`
- Method: `GET`
- Description: Returns a pageable project list.
- Request example: `GET /api/v1/inventory/projects?page=0&size=20&sort=code,asc`
- Response example: paginated project response.
- Possible errors: `500`.

### Get project change history

- URL: `/api/v1/inventory/projects/{id}/revisions?page=&size=`
- Method: `GET`
- Description: Returns the audited change history of one project, newest revision first. **Only
  changes made through this API are recorded.** A project updated by a master data event from
  `mto-configuration` leaves no revision, because that path writes with native SQL that the audit
  layer cannot observe. Do not present this history as complete for projects synchronised from
  another service.
- Request example: `GET /api/v1/inventory/projects/018f60be-1b9a-7cc3-8c6b-2f93e8c6a010/revisions?page=0&size=20`
- Response example: paginated revision response.
- Possible errors: `404`, `500`.