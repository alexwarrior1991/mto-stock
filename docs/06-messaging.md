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

## Where the business logic goes

`application/service/MasterDataEventHandler` is the extension point. The current implementation,
`application/service/impl/LoggingMasterDataEventHandler`, only logs. To react for real, replace it
with an implementation that does the work — the infrastructure consumer does not need to change,
because it depends on the interface.

Three transport concerns have to be settled by that implementation, and are deliberately unsolved
today because there is nothing to protect yet:

- **Idempotency.** Delivery is *at-least-once*: `operationId` identifies the originating operation
  and is what lets a repeat be discarded.
- **Order.** A redrive can replay something old; the `sequenceNumber` header says what was already
  applied for that aggregate.
- **Integrity.** Verifying `messageSignature` requires sharing
  `app.messaging.signature.secret` with `mto-configuration`.

| Layer | Class | Role |
| --- | --- | --- |
| `configuration/rabbitmq` | `MasterDataRabbitProperties` | Typed names of the topology |
| `configuration/rabbitmq` | `RabbitMqConfiguration` | Exchange, queue, bindings, DLX/DLQ, converter, listener factory |
| `infrastructure/messaging/rabbitmq` | `MasterDataRabbitMqNames`, `MasterDataMessageHeaders` | The contract's names, split by owner |
| `infrastructure/messaging/rabbitmq` | `MasterDataEventConsumer` | Thin listener: logs metadata and delegates |
| `application/dto/messaging` | `MasterDataChangedMessage`, `MasterDataChangedEvent`, `MasterDataOperation` | The message contract |
| `application/service` | `MasterDataEventHandler` | **Extension point for the business logic** |
| `application/service/impl` | `LoggingMasterDataEventHandler` | Placeholder implementation |
