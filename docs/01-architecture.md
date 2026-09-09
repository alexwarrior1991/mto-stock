# Architecture

Three layers, plus the wiring, each with its own package root under `com.alejandro.mtostock`. The
split is by **distance from the outside world**, not by technical role: a `Material` record that
knows nothing of JPA, the use cases that orchestrate it, and the frameworks that carry it in and out.

```
domain/
  model/                       Java records. No JPA, no Spring, no annotations.
application/
  dto/                         What crosses the API boundary, and nothing else.
  service/ + service/impl/     Use cases. Public interface, package-private impl.
  mapper/                      MapStruct: DTO ↔ domain ↔ entity.
  exception/                   Domain and business errors.
infrastructure/
  persistence/entity/          JPA @Entity.
  persistence/repository/      Spring Data + native SQL where correctness needs it.
  persistence/specification/   Composable filters for search endpoints.
  persistence/audit/           The Envers revision entity and its listener.
  messaging/rabbitmq/          Contract names, headers and the consumer.
  web/controller/              REST.
  web/exception/               GlobalExceptionHandler.
configuration/                 Wiring, not business: cache, messaging, rabbitmq, security.
```

**`domain/model` and `infrastructure/persistence/entity` share class names on purpose.** Both hold a
`Material`, a `Warehouse`, an `Assembly`, a `Reservation`. One is the domain rule, the other is the
row. Always read the import before touching one of them.

## Rules

- **Clean Architecture, in the direction of the arrows.** `domain` depends on nothing. `application`
  depends on `domain`. `infrastructure` depends on both. Nothing points the other way.
- **Constructor injection**, through Lombok's `@RequiredArgsConstructor`. No field injection, and no
  `@Autowired` on a field outside tests.
- **Service impls are package-private** behind a public interface. What is not part of the contract
  cannot be reached from another package by accident.
- **DTOs only across the API.** A JPA entity is never a request or a response body.
- **MapStruct for every mapping.** `EntityReferenceFactory` supplies id-only entity references for
  relationship fields, so a mapping never loads an association it does not need.
- **Bean Validation on the DTO**, business rules in the service. The first says the request is
  well-formed; the second says the operation makes sense.
- **One global exception handler.** Every error leaves as an `ApiErrorResponse`; see `04-rest-api.md`
  for the status each exception maps to.
- **Flyway for every schema change.** Hibernate runs `ddl-auto: validate` in all three profiles, so
  an entity annotation on its own changes nothing and breaks the boot instead.
- **SLF4J**, with the payload of a master data event at `DEBUG` and never at `INFO`: this service
  does not know what the publisher put inside it.
- **OpenAPI** on every endpoint, and pagination and filtering on every collection.

## Where correctness beats the layering

Two places write with native SQL through the repository instead of loading an entity and saving it,
and they are not shortcuts:

- `InventoryBalanceRepository` — stock is moved with a conditional `UPDATE ... WHERE` whose row count
  is the answer. A read-then-write lets two concurrent operations both pass a check in Java and
  overdraw the same stock.
- `ProjectRepository` — the master data watermark is compared inside the `where` of the writing
  statement. Between a `select` and an `update` fit two deliveries of the same event.

The cost is paid knowingly: Envers cannot see a native write, which is why `InventoryBalance` is not
audited and why a `project` synchronized from an event leaves no revision. Both are recorded in
`07-auditing.md`.

## Related documents

`02-domain-model.md` for the entities and the stock rule, `03-database.md` for the schema,
`04-rest-api.md` for the endpoints, `06-messaging.md` for the event channel, `07-auditing.md` for the
two audit layers. `CLAUDE.md` at the repository root is the short version of all of it.
