# Domain Model

## Entities

| Entity | What it is |
| --- | --- |
| `Material` | A component in the catalogue. Owns no stock columns. |
| `Supplier` | Who a stock entry came from. |
| `Warehouse` | A physical or logical storage location. |
| `Project` | What material is reserved and consumed for. Can be created through the API or synchronized from an execution package in `mto-configuration`; a synchronized project is owned by its source, so the API exposes `sourceService` and refuses to edit it. |
| `Assembly` | A **virtual** product: a catenary set that is never assembled in the warehouse. Has no stock of its own. |
| `AssemblyComponent` | One BOM line: this assembly needs this quantity of this material. |
| `StockMovement` | One signed row of the append-only ledger. Every inventory change is one of these. |
| `Reservation` | A positive quantity of a material held in a warehouse for a project. |
| `InventoryBalance` | The current-stock projection, derived from the two above. Not a business concept — see below. |
| `InboxMessage` | A master data message that arrived, and what was done with it. Not part of the inventory model at all. |

The framework-free records live in `domain/model`; the JPA classes in
`infrastructure/persistence/entity` **share their names**, so always read the import.

## Stock

Stock is never a column on `material` or `warehouse`. It is what the ledger adds up to:

```
physical = Entries + PositiveAdjustments + IncomingTransfers
         − Outputs − NegativeAdjustments − OutgoingTransfers

reserved = Σ quantity of the ACTIVE reservations
available = physical − reserved
```

Only `ACTIVE` reservations reduce availability. A reservation that is `RELEASED` or `CANCELLED` gives
the quantity back; one that is `CONSUMED` means the material actually left, so it lowers `physical`
too — through the ledger, like any material that leaves. Consuming writes the reservation's `OUTPUT`
either way: `POST /reservations/{id}/consume` writes it without reference or notes, and an output
registered against the reservation is that row. `ReservationEngine.consume` only moves the balance;
whoever calls it writes the row in the same transaction. Until V11 `/consume` lowered the balance
without the row, so the sum above stopped adding up; the migration wrote the missing outputs.

**That sum is not recomputed on every read.** `inventory_balance` holds it per material/warehouse
pair, and every movement or reservation updates it in the same transaction that writes the row. It is
a cache with a check constraint, not a second truth: the migration that created it rebuilt it from
`stock_movement` and the active reservations, and it can be rebuilt from them again. The ledger stays
the thing that is right; the projection is the thing that is fast. See `03-database.md`.

## Assemblies

An assembly has no stock and never will. Asking how many can be produced is a question about its
components, answered on demand:

```
producible = min over BOM lines of ⌊ available(component) / quantity per assembly ⌋
```

The limiting component is the one whose figure equals that minimum — which is the part worth showing
on screen, because it is what has to be bought. `BOMCalculationService` computes both.

The BOM is keyed by material: a material appears once, with its quantity per assembly
(`uq_assembly_component_assembly_material`; a request that repeats one is refused before reaching it).
An update sends the whole list and it replaces the old one matched by material: the line of a
material that stays keeps its identity and only its quantity changes, a material left out loses its
line, and a new one gets a new line. A line is never deleted and inserted again for the same
material.

## Multi-warehouse

Every stock figure is per material **and** warehouse; ask without a warehouse and the answer is the
sum across all of them. A transfer between warehouses is not a third kind of operation: it is two
linked ledger rows, one outgoing and one incoming, written in one transaction.
