# Messaging: master data events from `mto-configuration`

`mto-stock` consumes the master data change events that `mto-configuration` publishes to RabbitMQ.
Today the consumer **only logs what it receives**: it does not write to the database and does not
call any stock service. The channel is wired, observable and testable so that the business logic can
be added later in one place, without touching the transport.

## The contract

`mto-configuration` publishes with a transactional outbox and a relay that waits for the broker's
publisher confirms. Every message is a JSON envelope (`AsynchronousMessage`) carrying the business
payload (`MasterDataChangedEvent`) in `data`:

```json
{
  "operationId": "0f8b1f4c-3f6a-4a6d-9a2a-1c9f5f6f2b10",
  "referenceId": "station-42",
  "origin": "mto-configuration",
  "creationDate": "2026-09-01T10:15:30Z",
  "eventType": "MASTER_DATA_STATION_UPDATED",
  "data": {
    "entityName": "station",
    "entityId": "42",
    "operation": "UPDATED",
    "values": { "code": "BCN-SANTS", "name": "Barcelona Sants", "kp": 3.75 }
  },
  "messageHash": "9f2c1b0d…"
}
```

`values` is an open map on purpose: the publisher sends master data for very different entities
through a single message type. Translating it into this domain's types is the handler's job, not the
transport's.

Each message also carries AMQP headers, mirrored in `MasterDataMessageHeaders`:

| Header | What it is for |
| --- | --- |
| `eventType` | Same value as in the payload, so a router does not need to open the body |
| `aggregateType`, `aggregateId` | Entity and id that changed |
| `sequenceNumber` | Per-aggregate order. Delivery is *at-least-once* and a redrive can replay something older |
| `messageSignature`, `messageSignatureAlgorithm` | Signature over the bytes actually delivered — the only integrity check a consumer can redo |

`messageHash` inside the payload is **not** a signature: the publisher computes it on the object
before serializing, so verifying it would mean deserializing and re-serializing, and that round trip
does not preserve identity (a `BigDecimal` of `1.50` comes back as `1.5`). Use the header instead.

## Topology

| Object | Name | Owner |
| --- | --- | --- |
| Exchange | `mto.master-data.exchange` (topic, durable) | `mto-configuration` |
| Routing key | `mto.master-data.#` | `mto-configuration` |
| Queue | `mto.stock.master-data.queue` (classic, durable) | `mto-stock` |
| Dead letter exchange | `mto.stock.master-data.queue.dlx` (direct, durable) | `mto-stock` |
| Dead letter queue | `mto.stock.master-data.queue.dlq` (durable) | `mto-stock` |
| Dead letter routing key | `mto.stock.master-data.queue.dlq` | `mto-stock` |

Real routing keys are `mto.master-data.<entity>.<created|updated|deleted>`; this service binds the
full pattern because filtering per entity in the broker would mean redeclaring bindings every time
`mto-configuration`'s domain grows.

Two rules matter when changing any of this:

- **A queue belongs to whoever consumes it.** `mto-stock` declares its own queue and DLQ, not the
  publisher, because the consumer is the one who knows what TTL, limit and queue type it needs.
- **Redeclaring the exchange is idempotent only if the attributes match exactly.** Any difference
  makes the broker answer `PRECONDITION_FAILED` and close the channel, leaving the queue and the
  binding undeclared — and without a binding nothing is routed, with no visible error after startup.

Queue arguments are immutable once the queue exists. To put limits on a live queue, use a broker
policy instead of adding arguments here.

## Configuration

| Variable | Default | What it does |
| --- | --- | --- |
| `SPRING_RABBITMQ_HOST` | `localhost` (`rabbitmq` in Compose) | Broker host |
| `SPRING_RABBITMQ_PORT` | `5672` | AMQP port |
| `SPRING_RABBITMQ_USERNAME` / `SPRING_RABBITMQ_PASSWORD` | `guest` / `guest` | Broker credentials. In `prod` they have no default: a missing value stops startup |
| `SPRING_RABBITMQ_VIRTUAL_HOST` | `/` | Virtual host |
| `SPRING_RABBITMQ_LISTENER_PREFETCH` | `10` | Unacknowledged messages per consumer |
| `SPRING_RABBITMQ_LISTENER_CONCURRENCY` / `_MAX_CONCURRENCY` | `1` / `5` | Consumers per listener |
| `SPRING_RABBITMQ_LISTENER_MAX_RETRIES` | `3` | Attempts before the message goes to the DLQ |
| `APP_RABBITMQ_ENABLED` | `true` | Declares the topology **and** wires the consumer. `false` opens no connection at all |
| `APP_RABBITMQ_MASTER_DATA_LISTENER_ENABLED` | `true` | Only the consumer. `false` still declares the queue, which keeps collecting events |
| `APP_RABBITMQ_MASTER_DATA_EXCHANGE` | `mto.master-data.exchange` | Publisher's exchange |
| `APP_RABBITMQ_MASTER_DATA_QUEUE` | `mto.stock.master-data.queue` | This service's queue |
| `APP_RABBITMQ_MASTER_DATA_ROUTING_KEY` | `mto.master-data.#` | Binding pattern |
| `APP_RABBITMQ_MASTER_DATA_DEAD_LETTER_EXCHANGE` / `_QUEUE` / `_ROUTING_KEY` | `…queue.dlx` / `…queue.dlq` / `…queue.dlq` | Dead letter objects |
| `MANAGEMENT_HEALTH_RABBIT_ENABLED` | `false` | Include the broker in `/actuator/health`. Off by default so a missing broker does not report the API as down |

### Turning the listener off

```bash
# The application starts without a broker: no topology, no consumer, no connection.
APP_RABBITMQ_ENABLED=false ./mvnw spring-boot:run

# The topology is declared but nothing is consumed: the queue collects events.
APP_RABBITMQ_MASTER_DATA_LISTENER_ENABLED=false ./mvnw spring-boot:run
```

`application-test.yml` sets `app.rabbitmq.enabled=false`, so the test suite never needs a broker.

## Running it locally

RabbitMQ is part of the Compose stack (`rabbitmq:4-management-alpine`, AMQP on `5672`, management UI
on `15672`, credentials from `RABBITMQ_DEFAULT_USER` / `RABBITMQ_DEFAULT_PASS`):

```bash
cp .env.example .env      # then edit the credentials
docker compose up --build
```

If `mto-configuration`'s broker is already running, comment the `rabbitmq` service out and point
`SPRING_RABBITMQ_HOST` at it instead of starting a second one: it is the same exchange and the same
queues.

To publish a test message by hand, open <http://localhost:15672>, go to *Exchanges →
`mto.master-data.exchange` → Publish message*, set the routing key to
`mto.master-data.station.updated`, the `content_type` property to `application/json` and paste the
JSON from the contract section above. The application logs:

```
Master data message received: exchange=mto.master-data.exchange, routingKey=mto.master-data.station.updated, ...
Master data change received: eventType=MASTER_DATA_STATION_UPDATED, entity=station, entityId=42, operation=UPDATED, ...
```

## What is logged

Metadata goes to `INFO` and content to `DEBUG`. The `values` map is composed by
`mto-configuration` and this service cannot know what it contains; logging its values at `INFO`
would be deciding up front that nothing the publisher sends — now or when it adds new entities — is
sensitive. Field *names* do go to `INFO`: enough to see what is arriving, and not the data itself.
Set `LOGGING_LEVEL_APP=DEBUG` (the default in `dev`) to see full payloads.

## Failures and the dead letter queue

| Failure | What happens |
| --- | --- |
| The body does not deserialize into the contract | The container rejects it; it goes to the DLQ |
| Envelope without `data` | `AmqpRejectAndDontRequeueException`: straight to the DLQ, no retries spent — rereading the same message would not change it |
| The handler throws | Retried with backoff (`3` attempts by default) and then sent to the DLQ |

A rejected message is never requeued. That is set in code, not in YAML, on purpose: with Spring
AMQP's default a failing message goes back to the head of the queue and is redelivered forever, the
consumer spins, the DLQ never receives anything and the channel stays stuck on the first bad message.

Unknown fields do not fail deserialization. The publisher can add fields to the message without
coordinating with each consumer, and rejecting them would send perfectly valid messages to the DLQ
on the day `mto-configuration` is deployed.

## Idempotency: the inbox

`mto-configuration` publishes with an outbox, which guarantees an event is delivered **at least
once**. The inbox is the other half of that pair on this side: it guarantees the event is **applied
exactly once**, no matter how many times the broker delivers it.

The protection lives in the database, not in the code — a unique constraint on
`(message_id, source_service)` in `inbox_message`. Anything in memory, or an "does it exist? then
insert", has a race between the check and the write through which two simultaneous deliveries both
run the work.

### The idempotency key

`operationId` from the envelope, falling back to the AMQP `message_id` header.

`operationId` identifies the operation that produced the event in `mto-configuration` and travels
inside the payload the outbox stored once, so every redelivery carries the same value. The AMQP
`message_id` is the id of that outbox row — equally stable — and is the safety net if the payload
contract ever changes.

A message with neither is rejected straight to the DLQ. Applying it anyway would be worse than
dropping it: with no stable identifier there is no way to recognise the next delivery of the same
event, and the exactly-once promise breaks silently just when someone has started relying on it.

`payload_hash` is stored too, but only as a correlation aid. It is never the key: the publisher
already emits stable identifiers, and a hash of the payload would turn two legitimately identical
events into a duplicate.

### The flow

```
message → [inbox: record + claim] → handler → [inbox: mark processed] → ack
                    │                   │
       already applied → skip → ack     └─ throws → rollback
                                             → record FAILED (own transaction)
                                             → rethrow → retry → DLQ
```

One transaction covers recording, claiming, the work and the "processed" mark: they commit together
or nothing commits. There is no state where the inbox says a message was applied but its effect was
rolled back.

| Situation | What happens |
| --- | --- |
| First delivery | Row inserted, claimed, handler runs, row marked `PROCESSED`, `processing_attempts` = 1, message acked |
| Redelivery of an applied message | The conditional claim matches no rows, the handler never runs, `processing_attempts` unchanged, message acked. A duplicate is **not** an error and must not reach the DLQ |
| Handler fails | The transaction rolls back, the failure is recorded in its own transaction (`FAILED` + reason + `processing_attempts` + 1), and the exception is rethrown so the container retries and finally dead-letters |
| Redelivery of a failed message | Claimed again — its status is not `PROCESSED` — attempts keeps climbing and the previous reason is cleared. If it now works, it ends `PROCESSED` |
| Row stuck in `PROCESSING` | Claimed again. That state never commits on the normal path, so finding one means the process died mid-flight, and re-running it is the only way the event ever gets applied |

### Two concurrent deliveries

PostgreSQL serialises them, at a different point depending on when they arrive:

- **A first delivery and its duplicate at the same time.** The second cannot see the row the first
  has inserted but not committed, so what stops it is not a row lock but the unique index: its
  `insert ... on conflict` waits there until the first transaction finishes. If that one committed,
  the claim then finds the message already applied and skips. If it rolled back, the second inserts
  and takes the work — so a failure of the first does not lose the event.
- **Later deliveries, with the row already committed.** The insert does nothing and the claim
  `update` takes the row lock; the second waits there and, once released, PostgreSQL re-evaluates
  the `where` against the new version, sees `PROCESSED` and matches no rows.

Either way the work runs exactly once.

### Why the failed state is written in a separate transaction

The attempt's transaction has to roll back when the work fails — otherwise a message would be marked
as applied while its effect was undone. But that same rollback also removes the inbox row, so
marking the failure inside that transaction would leave no trace: the message would reach the DLQ
and the table would not remember why. Hence `recordFailure`, with its own transaction, called
**after** the attempt has finished.

Calling it from inside would be worse than useless: the attempt's transaction holds the row locked
until it commits or rolls back, the new one would wait for that lock, and the first would be waiting
for the second to return. Nobody moves, and no deadlock detector sees it, because one of the two is
waiting on a method call rather than on a lock.

### Inbox and DLQ are not the same thing

The DLQ holds messages the broker could not get processed. The inbox records what this service has
actually applied. A message can be in both — dead-lettered after its retries and recorded as
`FAILED` with the reason — and each answers a different question: the DLQ says "this is pending",
the inbox says "this is what happened to it".

### Payload

The `payload` column stores the JSON exactly as it arrived, character for character. It is `json`
and not `jsonb` on purpose: `jsonb` normalises on write — it reorders keys and collapses whitespace
— so what you read back is no longer what was received and its SHA-256 no longer matches
`payload_hash`. This table exists to answer "what exactly arrived", and fidelity is worth more here
than the `jsonb` operators, which nothing uses today: no query looks inside the payload. If one ever
needs to, an expression index over `payload::jsonb` or a generated column adds it without changing
what is stored.

## Routing, and where the business logic goes

`mto-configuration` publishes eight kinds of infrastructure master data, and this queue is bound to
`mto.master-data.#`, so all of them arrive:

| Entity name | What it is |
| --- | --- |
| `execution-package` | The work package: dates, length, company |
| `station` | Station, with its tracks, disconnectors and section insulators |
| `track` | Track, with its profiles |
| `profile` | The kilometre point where the catenary is supported: pole, foundation, anchorage |
| `cantilever` | Cantilever geometry: heights, stagger, arm angle |
| `steady-arm` | Steady arm |
| `disconnector` | Disconnector |
| `section-insulator` | Section insulator |

They describe the catenary being built, not materials or warehouses — there is no one-to-one
correspondence with anything in `mto-stock`. `DispatchingMasterDataEventHandler` therefore does not
decide anything: it looks at which entity changed and hands the message to the
`MasterDataEntityHandler` that claims it.

### `execution-package` → `project`

One handler is registered today, `ExecutionPackageMasterDataHandler`. An execution package is the
unit of work in `mto-configuration`, and a project is what materials are reserved and consumed
against here — the one entity of the eight with a real counterpart in this domain.

| Event | What happens |
| --- | --- |
| `CREATED` / `UPDATED` | The project is created or updated (one upsert; both operations take the same path, because an at-least-once redelivery of a `CREATED` must not fail) |
| `DELETED` | The project is **deactivated**, never deleted |

Only the name and the active flag are copied — all a `mto-stock` project can hold. The dates,
length, company, tracks and stations travel in the event and are dropped: giving them columns here
would duplicate `mto-configuration`'s model in a service that has no use for it. If they are ever
needed, they are in the payload the inbox keeps.

The project is matched on `(source_service, source_entity_id)`, not on its code. Those columns are
`NULL` for projects created through the API, which is what tells the two apart — a synchronized
project should not be edited by hand, since the next event overwrites it. The code is derived from
the source id (`EP-42`) because `project.code` is required and unique and the execution package
publishes none; deriving it keeps it stable across deliveries instead of tracking the name, which
does change. A code already taken by a hand-made project fails with an explanation rather than a
bare constraint violation.

Deletion deactivates because `reservation` and `stock_movement` reference `project` with
`on delete restrict`: a project with history could not be deleted anyway, and one without history
would take the record of why it existed with it. A deletion that matches no project is not an
error — the package may have been created and removed before this consumer existed.

**The other seven entities have no handler**, so their events are logged and ignored. What
`mto-stock` should do with a cantilever or a profile is a domain decision that has not been made
yet.

### Adding another handler

Create a `@Service` implementing `MasterDataEntityHandler`, the way
`ExecutionPackageMasterDataHandler` does:

```java
@Service
@RequiredArgsConstructor
class ExecutionPackageMasterDataHandler implements MasterDataEntityHandler {

    private final ProjectService projectService;

    @Override
    public String entityName() {
        return MasterDataEntityNames.EXECUTION_PACKAGE;
    }

    @Override
    public void onCreated(MasterDataChangedMessage message) {
        // message.data().values() carries the published fields
    }

    @Override
    public void onUpdated(MasterDataChangedMessage message) { … }
}
```

The dispatcher picks it up from the context — nothing to register, and the RabbitMQ consumer is not
touched. The three operation methods do nothing by default: reacting only to deletions, say, is a
legitimate choice and should not require writing two empty bodies to express it.

Two rules that implementation has to respect:

- **It runs inside the inbox transaction**, alongside the message record: everything commits or
  nothing does, and the message is applied exactly once however many times the broker delivers it.
  There is no need to check for repeats. Opening another transaction underneath — a `REQUIRES_NEW`,
  a second datasource, an outbound call — breaks that guarantee and leaves partial application to
  whoever wrote it.
- **`values()` is an open map** shaped by the publisher's own payload mapper. Translating it into
  this domain's types is the handler's job, and it is worth doing defensively: a field that exists
  today can disappear in the next `mto-configuration` deployment without this service hearing about
  it.

Two handlers claiming the same entity stop the application from starting: which one ran would
otherwise depend on classpath scanning order, which is a behaviour difference between two startups
of the same binary.

### Unknown entities are ignored, not failed

An event whose entity has no handler is logged and skipped. Treating it as an error would send most
of the normal traffic to the DLQ and turn every new entity type upstream into an outage here.

Such an event is still marked `PROCESSED` in the inbox, and that is correct: what to do with it was
already decided — nothing — and redelivering it would not change that. Nothing is lost either: the
inbox row keeps the original payload, so a handler written later can reprocess what was stored.

What *is* rejected straight to the DLQ is a message missing the minimum the contract promises — no
`data`, no `entityName`, or no `operation` — because without those the application layer cannot
decide anything, and rereading the same message will not add them.

### Transport concerns still open

- **Order.** A redrive can replay something old; the `sequenceNumber` header says what was already
  applied for that aggregate. The inbox stops the same message being applied twice, not an older
  message being applied after a newer one.
- **Integrity.** Verifying `messageSignature` requires sharing `app.messaging.signature.secret`
  with `mto-configuration`.

| Layer | Class | Role |
| --- | --- | --- |
| `configuration/rabbitmq` | `MasterDataRabbitProperties` | Typed names of the topology |
| `configuration/rabbitmq` | `RabbitMqConfiguration` | Exchange, queue, bindings, DLX/DLQ, converter, listener factory |
| `infrastructure/messaging/rabbitmq` | `MasterDataRabbitMqNames`, `MasterDataMessageHeaders` | The contract's names, split by owner |
| `infrastructure/messaging/rabbitmq` | `MasterDataEventConsumer` | Thin listener: logs metadata and delegates |
| `application/dto/messaging` | `MasterDataChangedMessage`, `MasterDataChangedEvent`, `MasterDataOperation` | The message contract |
| `application/service` | `MasterDataEventHandler` | Boundary between transport and application; one implementation |
| `application/service/impl` | `DispatchingMasterDataEventHandler` | Logs every change and routes it to the handler of its entity |
| `application/service` | `MasterDataEntityHandler` | **Extension point for the business logic**, one per entity |
| `application/dto/messaging` | `MasterDataEntityNames` | The eight entity names the publisher emits today |
| `application/dto/messaging` | `InboxMessageCommand`, `InboxProcessingResult` | Transport-free input and outcome of the inbox |
| `application/service` | `InboxMessageService` | Runs a piece of work at most once per message |
| `application/service` | `MasterDataEventProcessor` | What the consumer talks to: inbox + handler |
| `application/service/impl` | `InboxMessageServiceImpl`, `IdempotentMasterDataEventProcessor` | Idempotency and failure recording |
| `infrastructure/messaging/rabbitmq` | `InboxMessageCommandFactory` | AMQP metadata → command, and the idempotency key |
| `application/service/impl` | `ExecutionPackageMasterDataHandler` | The only handler today: keeps `project` in step with its execution package |
| `infrastructure/persistence` | `InboxMessage`, `InboxMessageStatus`, `InboxMessageRepository` | The `inbox_message` table and its atomic operations |
| `infrastructure/persistence` | `ProjectRepository.upsertFromMasterData` / `deactivateFromMasterData` | The atomic project sync, `project.source_service` + `source_entity_id` (`V5`) |
