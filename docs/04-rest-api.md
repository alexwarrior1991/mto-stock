# REST API

Every resource lives under `/api/v1/inventory`. The tables below are the complete surface; for
request and response bodies, field-by-field, see `frontend-api-guide.md`. Runnable examples are in
`http/inventory-api.http` and `postman/`, and the generated contract is at `/v3/api-docs` with
Swagger UI at `/swagger-ui.html`.

## Conventions

- **Paging.** Every collection endpoint takes `page`, `size` (default 20) and `sort` (e.g.
  `sort=code,asc`) and answers with a `PageResponse`: `content` plus a `page` block.
- **Text search.** The five catalogue lists (`/materials`, `/warehouses`, `/suppliers`,
  `/projects`, `/assemblies`) take an optional `search`: a case-insensitive *contains* on the code
  **or** the name, combined (AND) with the other filters of the list. A blank `search` is ignored.
- **Time.** All timestamps are ISO-8601 instants in UTC (`2026-08-01T00:00:00Z`). The `dateFrom` and
  `dateTo` filters are inclusive.
- **Errors.** Every failure is an `ApiErrorResponse` from `GlobalExceptionHandler`: `400` the request
  is malformed or fails Bean Validation, `401` no valid token, `403` the token lacks the role, `404`
  not found, `409` duplicate business code, insufficient stock or an `Idempotency-Key` reused with
  another body, `415` wrong content type, `422` a
  domain rule was violated (reservation, assembly, warehouse, movement or project), `500` anything else. `409`
  and `422` are kept apart on purpose: `409` says the warehouse cannot give you what you asked for,
  `422` says the operation itself does not make sense.
- **Idempotent writes.** `POST /reservations` and `POST /movements/outputs` take an optional
  `Idempotency-Key` header: 1 to 255 visible ASCII characters, chosen by the client (a UUID, or a key
  built from the client's own ids). A request that comes back with the same key and the same body
  writes nothing and answers `201` with what the first one created, as it is now — even when the
  stock it took is no longer there, so a retry after a timeout never reserves or takes the material
  out twice. The same key with a different body is `409 IDEM-001` and writes nothing; a malformed key
  is `400 VAL-001`. Keys belong to the authenticated caller and to the operation: another client, or
  the same key on the other endpoint, is a different request. A write that fails (`409 STK-001`,
  `422`...) does not consume its key, so the retry runs as a first request. Without the header, both
  endpoints behave as always.
- **Security.** All of it needs a Keycloak token. `GET` and `HEAD` need `STOCK_READ`; `POST`, `PUT`
  and `PATCH` need `STOCK_WRITE`; `DELETE` needs `STOCK_DELETE`. Posting an adjustment needs
  `STOCK_ADJUST` **on top of** `STOCK_WRITE`, because it is the one write that moves the balance with
  no counterpart document behind it.
- **There is no `DELETE` for master data.** Materials, suppliers, warehouses, projects and assemblies
  are retired by setting `active` to `false` through their `PUT`. Their ids are referenced by
  `stock_movement` and `reservation` with `on delete restrict`, so a real delete would either fail or
  take history with it.

## Materials

| Method | Path | Notes |
| --- | --- | --- |
| `POST` | `/materials` | `201` with `Location`. |
| `PUT` | `/materials/{id}` | Full replacement, including `active`. |
| `GET` | `/materials/{id}` | |
| `GET` | `/materials` | `search`, `code`, `name`, `active`, `warehouseId`, `belowMinimum` — all optional. |
| `GET` | `/materials/low-stock` | Optional `warehouseId`; omit it for stock across all warehouses. Shorthand for the search above with `active=true` and `belowMinimum=true`. |
| `GET` | `/materials/{id}/stock` | Optional `warehouseId`; omit it for the global figure. Returns physical, reserved and available, plus whether it is below its minimum. |
| `GET` | `/materials/{id}/movements` | The ledger for this material: `warehouseId`, `dateFrom`, `dateTo`, `user`. |
| `GET` | `/materials/{id}/revisions` | Change history, newest revision first. |

## Warehouses

| Method | Path | Notes |
| --- | --- | --- |
| `POST` | `/warehouses` | |
| `PUT` | `/warehouses/{id}` | |
| `GET` | `/warehouses/{id}` | |
| `GET` | `/warehouses` | `search`, `active` — both optional. |
| `GET` | `/warehouses/{id}/inventory` | Requires `materialId`. One material's stock in this warehouse. |
| `POST` | `/warehouses/transfers` | The same operation as `POST /movements/transfers`, under the warehouse resource. |
| `GET` | `/warehouses/{id}/revisions` | |

## Stock movements

The ledger is append-only: there is no `PUT` and no `DELETE`. A mistake is corrected with an
adjustment, which leaves both rows visible.

| Method | Path | Notes |
| --- | --- | --- |
| `POST` | `/movements/entries` | Material into a warehouse. Optional `supplierId`. |
| `POST` | `/movements/outputs` | Material out. Optional `projectId`; with a `reservationId` the movement consumes that reservation, and the quantity has to match it exactly. Optional `Idempotency-Key` (see Conventions). |
| `POST` | `/movements/adjustments` | `direction` is `POSITIVE` or `NEGATIVE`. Needs `STOCK_ADJUST`. |
| `POST` | `/movements/transfers` | Between two warehouses. Answers `201` with **two** movements, the outgoing and the incoming one, linked by `related_movement_id`. |
| `GET` | `/movements/{id}` | |
| `GET` | `/movements` | `movementType`, `warehouseId`, `projectId`, `materialId`, `dateFrom`, `dateTo`, `user`. |

## Reservations

| Method | Path | Notes |
| --- | --- | --- |
| `POST` | `/reservations` | Only reduces availability while `ACTIVE`. Optional `Idempotency-Key` (see Conventions). |
| `PUT` | `/reservations/{id}` | Warehouse, project and quantity. Only on an `ACTIVE` reservation. |
| `DELETE` | `/reservations/{id}` | Cancels it — `CANCELLED`. Returns the reservation, not `204`. Needs `STOCK_DELETE`. |
| `POST` | `/reservations/{id}/release` | Gives the stock back without it leaving — `RELEASED`. |
| `POST` | `/reservations/{id}/consume` | The material left against it — `CONSUMED`. |
| `GET` | `/reservations/{id}` | |
| `GET` | `/reservations` | `warehouseId`, `status`, `projectId`, `materialId`. |
| `GET` | `/reservations/{id}/revisions` | |

`DELETE` and the two `POST`s all end the reservation and all free the reserved quantity; they differ
in what happened, which is what the history is for. Only `consume` also lowers physical stock.

## Assemblies

An assembly is a virtual product defined by its bill of materials. It never has stock of its own.

| Method | Path | Notes |
| --- | --- | --- |
| `POST` | `/assemblies` | Must carry at least one BOM component. |
| `PUT` | `/assemblies/{id}` | Replaces the BOM as well. |
| `GET` | `/assemblies/{id}` | |
| `GET` | `/assemblies` | `search`, `code`, `name`, `active`. |
| `GET` | `/assemblies/{id}/availability` | Requires `warehouseId`. How many could be built right now from component stock, and which component is the limiting one. |
| `GET` | `/assemblies/{id}/production-capacity` | The same response, under the ERP term. |
| `GET` | `/assemblies/{id}/revisions` | Changing the bill of materials revises the assembly too. |

## Suppliers

| Method | Path | Notes |
| --- | --- | --- |
| `POST` | `/suppliers` | |
| `PUT` | `/suppliers/{id}` | |
| `GET` | `/suppliers/{id}` | |
| `GET` | `/suppliers` | `search`, `active` — both optional. |
| `GET` | `/suppliers/{id}/revisions` | |

## Projects

| Method | Path | Notes |
| --- | --- | --- |
| `POST` | `/projects` | |
| `PUT` | `/projects/{id}` | Refused with `422` `PRJ-001` when the project is synchronized from master data: its source owns it and the next event would overwrite the change. |
| `GET` | `/projects/{id}` | |
| `GET` | `/projects` | `search`, `active` — both optional. |
| `GET` | `/projects/{id}/revisions` | Covers the REST path only — a project synchronized from an execution package leaves no revision. See the `Project` gap in `07-auditing.md`. |

A project can also arrive from `mto-configuration` as an execution package, in which case nothing
creates it through this API. See `06-messaging.md`. The response says so: `sourceService`
(`mto-configuration`, or `null` for a project created here) and `synchronizedFromMasterData`, the
same fact as a flag, so a client can show those projects read-only instead of discovering the
`422` on save.

## Change history

`GET /api/v1/inventory/<resource>/{id}/revisions` on materials, suppliers, warehouses, projects,
assemblies and reservations. It is `/revisions` and not `/history` because `/movements` already means
the stock ledger, and the two answer different questions: the ledger says what moved, the revisions
say who changed the record and what it said before. Details in `07-auditing.md`.

## Outside the API prefix

`/actuator/health` and `/actuator/info` are open, for the orchestrator's probes. The rest of Actuator
needs `OPS_METRICS`, and the endpoints that change something need `OPS_WRITE`. `/swagger-ui.html` and
`/v3/api-docs` are only exposed where the configuration says so: in a deployed environment the
generated contract is the best possible map for anyone looking for a way in.
