package com.alejandro.mtostock.infrastructure.messaging;

import com.alejandro.mtostock.application.dto.messaging.AsynchronousMessage;
import com.alejandro.mtostock.application.dto.messaging.DomainEvent;
import com.alejandro.mtostock.application.dto.messaging.MessageActor;
import com.alejandro.mtostock.application.dto.stock.StockAdjustmentDirection;
import com.alejandro.mtostock.application.service.impl.StockEvents;
import com.alejandro.mtostock.infrastructure.messaging.outbox.AsynchronousMessageFactory;
import com.alejandro.mtostock.infrastructure.messaging.outbox.AsynchronousMessageHashService;
import com.alejandro.mtostock.infrastructure.messaging.outbox.MessageContextResolver;
import com.alejandro.mtostock.infrastructure.messaging.rabbitmq.StockRabbitMqNames;
import com.alejandro.mtostock.infrastructure.persistence.entity.AuditableEntity;
import com.alejandro.mtostock.infrastructure.persistence.entity.Material;
import com.alejandro.mtostock.infrastructure.persistence.entity.Project;
import com.alejandro.mtostock.infrastructure.persistence.entity.Reservation;
import com.alejandro.mtostock.infrastructure.persistence.entity.StockMovement;
import com.alejandro.mtostock.infrastructure.persistence.entity.StockMovementType;
import com.alejandro.mtostock.infrastructure.persistence.entity.Warehouse;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Los ejemplos de {@code docs/messaging/examples} son el contrato tal como lo ven los consumidores:
 * {@code mto-notification} los copia como fixtures. Un ejemplo escrito a mano se desalinea del codigo
 * sin que nadie lo note; este test lo evita construyendo cada mensaje con {@code StockEvents} y la
 * factoria real y comparandolo con el fichero, salvo las dos claves que cambian en cada ejecucion
 * (la fecha y, con ella, la huella), que se comprueban aparte: la huella del fichero es la que un
 * consumidor calcularia sobre sus siete claves originales.
 *
 * <p>Un evento nuevo o una clave nueva cambian el ejemplo en el mismo commit. Para regenerarlos:
 * {@code MESSAGING_EXAMPLES_WRITE=true ./mvnw test -Dtest=MessagingContractExamplesTest}, y se
 * revisa el diff como cualquier cambio de contrato.</p>
 */
class MessagingContractExamplesTest {

    private static final Path EXAMPLES = Path.of("docs", "messaging", "examples");

    private static final boolean WRITE = "true".equalsIgnoreCase(System.getenv("MESSAGING_EXAMPLES_WRITE"));

    private static final Instant CREATION_DATE = Instant.parse("2026-09-29T09:00:00Z");

    private static final MessageActor OPERATOR = MessageActor.of("6f1b1c8e-0000-4000-8000-000000000041", "almacen.operario");
    private static final MessageActor MANAGER = MessageActor.of("6f1b1c8e-0000-4000-8000-000000000042", "almacen.responsable");
    /** La cuenta de servicio con la que mto-maintenance reserva y libera material: un SERVICE para el sobre. */
    private static final MessageActor MAINTENANCE = MessageActor.of("6f1b1c8e-0000-4000-8000-000000000043", "service-account-mto-maintenance-svc");
    private static final String REQUEST_CORRELATION_ID = "8c3b8c1a-1111-4222-8333-444444444444";

    /** Con decimales como BigDecimal a los dos lados, {@code 4.000000} del fichero es {@code 4.000000} del mensaje. */
    private final ObjectMapper objectMapper = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    private final AsynchronousMessageHashService hashService = new AsynchronousMessageHashService(objectMapper);

    // ----------------------------------------------------------------------------- materials

    @Test
    void materialBelowMinimum() throws IOException {
        Material material = material();

        // Una salida de 4 con 12 disponibles y un minimo de 10: el material acaba de cruzar por debajo.
        check("material-below-minimum.json", "f0000000-0000-4000-8000-000000000001", OPERATOR, REQUEST_CORRELATION_ID,
                StockEvents.materialBelowMinimum(material, warehouse().getId(), StockEvents.OPERATION_OUTPUT,
                        new BigDecimal("4.000000"), new BigDecimal("12.000000"), new BigDecimal("8.000000")));
    }

    // -------------------------------------------------------------------------- reservations

    @Test
    void reservationCancelled() throws IOException {
        // La reserva la creo mto-maintenance con su cuenta de servicio y la cancela una persona del almacen.
        Reservation reservation = reservation();
        reservation.cancel(Instant.parse("2026-09-29T09:00:00Z"));

        check("reservation-cancelled.json", "f0000000-0000-4000-8000-000000000002", MANAGER, REQUEST_CORRELATION_ID,
                StockEvents.reservationCancelled(reservation));
    }

    @Test
    void reservationReleased() throws IOException {
        // La libera el mismo mto-maintenance que la creo, al cancelar la orden: nada que avisar.
        Reservation reservation = reservation();
        reservation.release(Instant.parse("2026-09-29T09:00:00Z"));

        check("reservation-released.json", "f0000000-0000-4000-8000-000000000003", MAINTENANCE, REQUEST_CORRELATION_ID,
                StockEvents.reservationReleased(reservation));
    }

    // --------------------------------------------------------------------------- adjustments

    @Test
    void adjustmentRegistered() throws IOException {
        StockMovement adjustment = StockMovement.builder().material(material()).warehouse(warehouse())
                .type(StockMovementType.NEGATIVE_ADJUSTMENT).quantity(new BigDecimal("3.000000"))
                .occurredAt(Instant.parse("2026-09-29T08:45:00Z")).externalReference("RECUENTO-2026-39")
                .notes("Recuento semanal: 3 unidades danadas").build();
        id(adjustment, "e0000000-0000-4000-8000-000000000001");

        check("adjustment-registered.json", "f0000000-0000-4000-8000-000000000004", MANAGER, REQUEST_CORRELATION_ID,
                StockEvents.adjustmentRegistered(adjustment, StockAdjustmentDirection.NEGATIVE));
    }

    // ----------------------------------------------------------------------------- fixtures

    private static Material material() {
        Material material = Material.builder().code("GA70").name("Grapa de atirantado 70").unitOfMeasure("ud")
                .minimumStockLevel(new BigDecimal("10.000000")).build();
        id(material, "b0000000-0000-4000-8000-000000000001");
        return material;
    }

    private static Warehouse warehouse() {
        Warehouse warehouse = Warehouse.builder().code("ALM-HZL").name("Almacen Herzliya").build();
        id(warehouse, "c0000000-0000-4000-8000-000000000001");
        return warehouse;
    }

    private static Project project() {
        Project project = Project.builder().code("EP-6").name("Paquete de ejecucion 6").build();
        id(project, "50000000-0000-4000-8000-000000000001");
        return project;
    }

    private static Reservation reservation() {
        Reservation reservation = Reservation.builder().material(material()).warehouse(warehouse()).project(project())
                .quantity(new BigDecimal("4.000000")).reservedAt(Instant.parse("2026-09-28T10:00:00Z")).build();
        id(reservation, "d0000000-0000-4000-8000-000000000001");
        audit(reservation, "service-account-mto-maintenance-svc", Instant.parse("2026-09-28T10:00:00Z"));
        return reservation;
    }

    private static void audit(AuditableEntity entity, String actor, Instant at) {
        entity.setCreatedBy(actor);
        entity.setCreatedAt(at);
        entity.setUpdatedBy(actor);
        entity.setUpdatedAt(at);
    }

    private static void id(Object entity, String id) {
        ReflectionTestUtils.setField(entity, "id", UUID.fromString(id));
    }

    // ------------------------------------------------------------------------------ helpers

    private void check(String fileName, String operationId, MessageActor actor, String correlationId, DomainEvent event) throws IOException {
        MessageContextResolver contextResolver = mock(MessageContextResolver.class);
        when(contextResolver.currentActor()).thenReturn(actor);
        when(contextResolver.currentCorrelationId()).thenReturn(correlationId);
        AsynchronousMessageFactory factory = new AsynchronousMessageFactory(hashService, contextResolver, "mto-stock");

        AsynchronousMessage<DomainEvent> message = factory.create(UUID.fromString(operationId),
                event.entityName() + "-" + event.entityId(),
                StockRabbitMqNames.eventType(event.entityName(), event.eventName()), event);

        Path file = EXAMPLES.resolve(fileName);
        if (WRITE) {
            Files.createDirectories(EXAMPLES);
            Files.writeString(file, pretty(tree(withCreationDate(message, CREATION_DATE)), 0) + "\n");
        }

        assertThat(file).as("el ejemplo %s tiene que estar versionado", fileName).exists();
        assertSameAsExample(message, objectMapper.readTree(Files.readString(file)));
    }

    /**
     * Compara el mensaje con el ejemplo por el texto que viaja de verdad (releido como arbol para
     * que el orden de las claves no cuente), y la huella del ejemplo con la que se obtiene de sus
     * propias siete claves, que es la unica forma de que el ejemplo lleve una huella cierta.
     */
    private void assertSameAsExample(AsynchronousMessage<DomainEvent> message, JsonNode example) {
        ObjectNode produced = tree(message);

        assertThat(Instant.parse(produced.get("creationDate").asText())).isNotNull();
        assertThat(produced.get("messageHash").asText()).matches("[0-9a-f]{64}");

        // La fecha es la de ahora y la huella la incluye: se toman las del ejemplo para comparar lo
        // demas, que es lo que el ejemplo fija.
        produced.put("creationDate", example.get("creationDate").asText());
        produced.put("messageHash", example.get("messageHash").asText());

        assertThat(produced).isEqualTo(example);

        AsynchronousMessage<DomainEvent> asInExample = withCreationDate(message, Instant.parse(example.get("creationDate").asText()));
        assertThat(example.get("messageHash").asText())
                .as("la huella del ejemplo es la de sus siete claves originales, con la fecha del ejemplo")
                .isEqualTo(asInExample.messageHash());
    }

    private AsynchronousMessage<DomainEvent> withCreationDate(AsynchronousMessage<DomainEvent> message, Instant creationDate) {
        AsynchronousMessage<DomainEvent> dated = new AsynchronousMessage<>(message.operationId(), message.referenceId(), message.origin(),
                creationDate, message.eventType(), message.data(), "PENDING", message.actor(), message.correlationId());
        return dated.withMessageHash(hashService.calculate(dated));
    }

    private ObjectNode tree(AsynchronousMessage<DomainEvent> message) {
        return (ObjectNode) objectMapper.readTree(objectMapper.writeValueAsString(message));
    }

    /** Dos espacios, una clave por linea y los elementos de una lista tambien: un diff legible en una revision. */
    private static String pretty(JsonNode node, int depth) {
        String pad = "  ".repeat(depth + 1);
        if (node.isObject()) {
            if (node.isEmpty()) {
                return "{}";
            }
            List<String> fields = new ArrayList<>();
            node.properties().forEach(field -> fields.add(pad + "\"" + field.getKey() + "\": " + pretty(field.getValue(), depth + 1)));
            return "{\n" + String.join(",\n", fields) + "\n" + "  ".repeat(depth) + "}";
        }
        if (node.isArray()) {
            if (node.isEmpty()) {
                return "[]";
            }
            List<String> items = new ArrayList<>();
            node.forEach(item -> items.add(pad + pretty(item, depth + 1)));
            return "[\n" + String.join(",\n", items) + "\n" + "  ".repeat(depth) + "]";
        }
        return node.toString();
    }
}
