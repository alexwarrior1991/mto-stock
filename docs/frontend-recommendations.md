# Frontend Recommendations

These recommendations are framework-neutral and can be applied with React, Angular, Vue, Svelte, or any modern frontend stack.

## Suggested frontend architecture

- Keep API communication isolated from UI components.
- Organize code around domain modules: materials, warehouses, stock movements, assemblies, reservations, suppliers, and projects.
- Treat backend DTOs as API boundary models; map them to view models when screens need derived or formatted values.
- Centralize cross-cutting concerns such as base URL, authentication headers, error normalization, pagination helpers, and request cancellation.

## Folder organization

Example structure:

```text
src/
  api/
    http-client.ts
    api-error.ts
    pagination.ts
    inventory/
      materials.api.ts
      warehouses.api.ts
      stock-movements.api.ts
      assemblies.api.ts
      reservations.api.ts
      suppliers.api.ts
      projects.api.ts
  domain/
    materials/
      material.dto.ts
      material.model.ts
      material.mapper.ts
    warehouses/
    stock-movements/
    assemblies/
    reservations/
    suppliers/
    projects/
  state/
    inventory/
  features/
    inventory/
  shared/
    components/
    validation/
    formatting/
```

Use the same idea in non-TypeScript projects: keep `api`, `domain`, `state`, `features`, and `shared` boundaries separate.

## State management recommendations

- Use server-state tooling or a repository/cache layer for API data.
- Cache list queries by endpoint plus filter, page, size, and sort parameters.
- Invalidate related caches after mutations:
  - Material create/update: invalidate material lists and material detail.
  - Stock entry/output/adjustment/transfer: invalidate stock, movement history, low-stock material lists, warehouse inventory, and assembly availability.
  - Reservation create/update/cancel/release/consume: invalidate reservation lists, material stock, warehouse inventory, and availability screens.
- Keep form state local to forms unless multiple screens must share drafts.
- Keep selected IDs in route parameters whenever possible so pages are directly linkable.

## API service structure

- Create one service module per API folder.
- Define a shared `request` wrapper that sets:
  - `baseUrl`
  - `Accept: application/json`
  - `Content-Type: application/json` for body requests
  - `Authorization: Bearer <jwt>` header, from the Keycloak token
  - optional correlation/request ID
- Return typed promises or observable streams with a consistent shape.
- Normalize errors once in the shared client instead of duplicating error parsing in components.
- Keep endpoint path builders close to each module to avoid hard-coded URLs in views.

Example service shape:

```ts
type PageRequest = {
  page?: number;
  size?: number;
  sort?: string[];
};

type PageResponse<T> = {
  content: T[];
  page: {
    number: number;
    size: number;
    totalElements: number;
    totalPages: number;
    first: boolean;
    last: boolean;
  };
};

type ApiError = {
  timestamp: string;
  status: number;
  error: string;
  message: string;
  path: string;
  method: string;
  errorCode: string;
  correlationId: string;
  validationErrors: Array<{ field: string; message: string }>;
};
```

## Error handling recommendations

- Parse backend errors into a shared `ApiError` type.
- Display field-level messages from `validationErrors` next to matching form inputs.
- Display `message` as the general fallback error text.
- Log or expose `correlationId` in support/debug UI so backend logs can be correlated.
- Handle status codes consistently:
  - `400`: show validation/filter correction UI.
  - `404`: show not-found screen or refresh stale lists.
  - `409`: show duplicate-code or insufficient-stock message.
  - `422`: show domain-rule explanation and keep the user on the workflow screen.
  - `500`: show generic retry/support message.

## Pagination strategy

- Use backend pagination for all list screens.
- Store `page`, `size`, `sort`, and filters in the route query string for bookmarkable list pages.
- Reset `page` to `0` whenever filters or search terms change.
- Prefer explicit sort defaults per screen, for example `code,asc` for catalogues and `occurredAt,desc` for movements.
- Keep previous page data visible while loading the next page when the framework/state library supports it.

## Search strategy

- Use server-side filters exposed by each endpoint rather than downloading all data.
- Debounce text search inputs before calling endpoints such as materials or assemblies.
- Validate UUID and date filters on the client before sending requests.
- Use exact enum values from the API: reservation status values are `ACTIVE`, `RELEASED`, and `CANCELLED`; stock adjustment direction values are `POSITIVE` and `NEGATIVE`.
- For stock movement searches, keep date range filters in UTC ISO-8601 format.

## DTO usage

- Keep DTO names aligned with backend concepts:
  - `MaterialRequest`, `MaterialResponse`, `MaterialStockResponse`
  - `WarehouseRequest`, `WarehouseResponse`
  - `StockMovementEntryRequest`, `StockMovementOutputRequest`, `StockMovementAdjustmentRequest`, `StockMovementTransferRequest`, `StockMovementResponse`
  - `AssemblyRequest`, `AssemblyResponse`, `AssemblyAvailabilityResponse`
  - `ReservationRequest`, `ReservationUpdateRequest`, `ReservationResponse`
  - `SupplierRequest`, `SupplierResponse`
  - `ProjectRequest`, `ProjectResponse`
- Keep numeric quantities as decimal-safe values. If the UI performs arithmetic, use a decimal library instead of binary floating-point where precision matters.
- Keep timestamps as strings at the DTO boundary and convert only in presentation or date utilities.
- Authentication is a Keycloak-issued JWT sent on every request. Attach it in the shared HTTP client, not in each service, and handle `401` by refreshing or re-authenticating rather than surfacing it as a generic failure.