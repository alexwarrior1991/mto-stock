# Auditing

`mto-stock` records two different things, and it is worth being precise about which answers which
question:

| Layer | What it answers | Where it lives |
|---|---|---|
| Spring Data JPA auditing | *Who touched this row last, and when?* | `created_at` / `updated_at` / `created_by` / `updated_by` on every table |
| Hibernate Envers | *What did it say before, and who changed it?* | `audit_revision` plus one `<table>_aud` twin per audited entity |

Both resolve the actor through the same class, `configuration/AuditActorResolver`, so the two can
never disagree about who wrote something. The `system` / `unknown` distinction it makes is
deliberate and documented there.

## What is audited

Seven entities carry `@Audited`:

`Material`, `Supplier`, `Warehouse`, `Project`, `Assembly`, `AssemblyComponent`, `Reservation`.

These are the mutable ones. The case that motivated the whole thing is `AssemblyComponent`: a
`PUT /assemblies/{id}` replaces the component list through `orphanRemoval`, so before Envers a
deleted bill-of-materials line left **no trace anywhere** — not in the ledger, not in the audit
columns. `Reservation` is the second: `quantity` is edited in place and `status` walks a lifecycle of
which only the final state survived.

## What is deliberately not audited, and why

Three entities carry no `@Audited`, and this is not an oversight:

- **`StockMovement`** is already an append-only immutable ledger — it is only ever
  `save(new StockMovement(...))`, with no update or delete endpoints. A `stock_movement_aud` twin
  would double the storage of the largest table in the system without recording a single new fact.
- **`InventoryBalance`** and **`InboxMessage`** are written **exclusively with native SQL**
  (`@Modifying(nativeQuery = true)` conditional updates in `InventoryBalanceRepository` and
  `InboxMessageRepository` — the row-count idiom that makes them correct under concurrency). Envers
  hooks the persistence context, so it would never see those writes. Their twins would sit
  permanently empty, and an empty history table does not read as "no auditing here", it reads as
  "this never changed". That is worse than not having it.

`AuditableEntity` therefore carries **no** `@Audited`: it is the mapped superclass of all ten
entities, so annotating it would sweep in all three. `JpaEntityModelTest` pins the split so it cannot
drift silently.

Its four audit fields carry `@NotAudited`, so the `_aud` twins do not repeat
`created_at`/`updated_at`/`created_by`/`updated_by`: `audit_revision` already records who and when,
once per revision instead of once per changed row per table. The annotation is explicit rather than
relying on the default, because what Envers does with the properties of a non-`@Audited` mapped
superclass has changed between versions — leaving it to the version turns a Hibernate upgrade into a
startup failure over a column that is missing from, or surplus to, a twin.

## The `Project` gap

A `project` that changes because of a master data event from `mto-configuration` produces **no
revision**. `ProjectRepository.upsertFromMasterData` and `deactivateFromMasterData` are native SQL on
purpose — the sequence-number watermark is checked inside the statement's own `where`, because
checking it in Java and then writing leaves room for a second delivery in between — and native SQL
never reaches the persistence context, which is where Envers listens.

So `project_aud` records only the REST path: projects created and edited by people. This is a known
and accepted gap, not a bug; closing it would mean giving up the atomic watermark check, which is a
worse trade. The traceability of those events is already covered by `inbox_message`. If it ever has
to be closed, the way is a database trigger writing `project_aud` and `audit_revision`, not a change
to the repository.

## What a revision records

`audit_revision` holds one row per **transaction** that touched an audited entity:

| Column | Notes |
|---|---|
| `id` | revision number, shared by every entity changed in that transaction |
| `timestamp` | epoch millis — the type `@RevisionTimestamp` accepts on every Envers version; `AuditRevision.getRevisionInstant()` returns it as an `Instant` |
| `username`, `user_id` | the same actor as `updated_by`; `user_id` is the token `sub`, which survives a rename in Keycloak |
| `source` | `HTTP`, `MESSAGING`, `SYSTEM` or `BASELINE` |
| `correlation_id` | the `X-Correlation-Id` header on the HTTP path, the message id on the messaging path — `source` says which |
| `ip_address`, `user_agent`, `request_method`, `request_uri` | HTTP path only; `ip_address` is the first `X-Forwarded-For` hop, because behind the gateway `getRemoteAddr()` would be the gateway |

`AuditRevisionListener` fills these. It injects nothing on purpose: Hibernate instantiates a
`RevisionListener` while the `SessionFactory` is being built, i.e. mid context refresh, so everything
it reads is a static holder. That also makes it unit-testable without a container.

### The baseline revision

`V7` inserts one synthetic revision (`id = 1`, `source = 'BASELINE'`) holding a snapshot of every
existing row. It does **not** claim anyone created anything at that moment — it says "this is how
things stood when history started being kept". Without it, the first change to a pre-Envers row would
leave a `MOD` revision with the new state and no predecessor, and the prior state would be lost for
good.

## Reading the history

```
GET /api/v1/inventory/materials/{id}/revisions
GET /api/v1/inventory/suppliers/{id}/revisions
GET /api/v1/inventory/warehouses/{id}/revisions
GET /api/v1/inventory/projects/{id}/revisions
GET /api/v1/inventory/assemblies/{id}/revisions
GET /api/v1/inventory/reservations/{id}/revisions
```

Newest revision first, paginated with the usual `page` / `size`. Each entry pairs the revision
metadata with the resource in the shape the normal `GET` returns it, so clients do not learn a second
way to read a material.

It is called `/revisions` and not `/history` because `GET /materials/{id}/movements` is already
documented as "movement history" and they are different things: that is the stock ledger, this is who
changed the record.

Authorization needs no new rule — `SecurityConfiguration` already requires `STOCK_READ` for every
`GET` under the API prefix. Note the consequence: **anyone who can read the catalogue can read who
changed it, from which IP and with which user agent.** If that is too broad, the options are a
dedicated `STOCK_AUDIT` role (which needs a matching client role in `mto-platform` and in the test
realm) or dropping `ip_address` / `user_agent`, which are the least useful of the columns.

Note that in a revision entry the resource's own audit block
(`createdAt`/`updatedAt`/`createdBy`/`updatedBy`) comes back empty, by the `@NotAudited` decision
above. The per-revision who-and-when is in `revision`.

A deletion revision carries the last known values rather than nulls: that is
`store_data_at_delete: true`, and it is the difference between "something was deleted" and "four
units of this material were deleted".

Changing an assembly's component list also produces a `MOD` revision of the assembly itself, even
when its own columns did not change (`revision_on_collection_change`, on by default). This is
desirable — what the assembly is made of changed — but surprising, so `EnversAuditDataJpaTest` pins
it.

## Maintenance rule

> Every migration that adds, drops, renames or retypes a column on `material`, `supplier`,
> `warehouse`, `project`, `assembly`, `assembly_component` or `reservation` **must apply the same
> change to its `<table>_aud` twin in the same migration**, with two differences: the `_aud` column is
> always nullable, and it carries no `CHECK`, `UNIQUE` or foreign key.

Hibernate builds the Envers mapping from the entity, so a base column added without its twin makes
`ddl-auto: validate` fail and **the application does not start** — in whichever environment runs the
migration first. Adding a column to an unaudited entity (`stock_movement`, `inventory_balance`,
`inbox_message`) needs no twin. Widening a `varchar` is the dangerous case: `validate` does not check
lengths, so a half-applied change boots fine and truncates history later.

`mto-configuration` learned this the hard way; its `V8` and `V9` carry shouted warnings about it.

## Deliberately deferred

- **A modified-entities table.** Envers can track which entity types changed in a revision
  (`track_entities_changed_in_revision`), which is what you would want for a cross-entity "what did
  this user change last Tuesday" query. There is no consumer for it yet, and adding it later is a
  pure addition: one field, one table, one property. `mto-configuration` hand-rolled an equivalent
  that keys on `Long` ids and so cannot be copied here, where ids are `UUID`.
- **`spring-data-envers`.** Its `RevisionRepository` requires swapping the repository factory bean
  for every repository in the scanned packages — including the ones whose correctness rests on native
  conditional SQL — in exchange for little more than `AuditReaderFactory` already gives.
  `mto-configuration` has it on the classpath, unused.
- **A retention policy.** There is none: history grows without bound. Master data changes rarely, so
  this is slow, but `reservation_aud` is the one to watch. Purging means deleting from the `_aud`
  tables first and then the orphaned `audit_revision` rows — the `rev` foreign keys dictate that
  order.
