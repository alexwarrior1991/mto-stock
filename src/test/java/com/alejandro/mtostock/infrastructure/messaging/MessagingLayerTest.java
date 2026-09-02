package com.alejandro.mtostock.infrastructure.messaging;

import com.alejandro.mtostock.application.dto.messaging.MasterDataChangedEvent;
import com.alejandro.mtostock.application.dto.messaging.MasterDataChangedMessage;
import com.alejandro.mtostock.application.dto.messaging.MasterDataOperation;
import com.alejandro.mtostock.application.dto.messaging.InboxMessageCommand;
import com.alejandro.mtostock.application.dto.messaging.InboxProcessingResult;
import com.alejandro.mtostock.application.service.MasterDataEventProcessor;
import com.alejandro.mtostock.configuration.rabbitmq.MasterDataRabbitProperties;
import com.alejandro.mtostock.configuration.rabbitmq.RabbitMqConfiguration;
import com.alejandro.mtostock.infrastructure.messaging.rabbitmq.InboxMessageCommandFactory;
import com.alejandro.mtostock.infrastructure.messaging.rabbitmq.MasterDataEventConsumer;
import com.alejandro.mtostock.infrastructure.messaging.rabbitmq.MasterDataMessageHeaders;
import com.alejandro.mtostock.infrastructure.messaging.rabbitmq.MasterDataRabbitMqNames;
import com.alejandro.mtostock.infrastructure.messaging.rabbitmq.RabbitListenerContainerFactoryNames;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Comprobaciones del canal de datos maestros que no necesitan un broker: el contrato del mensaje
 * tal y como lo publica {@code mto-configuration}, el reparto de responsabilidades entre listener y
 * manejador, y el cableado de la topología.
 *
 * <p>Ninguna levanta RabbitMQ a propósito. Un test que dependiera del broker dejaría de comprobar
 * lo único que aquí importa —que las piezas encajan— para comprobar si hay un contenedor
 * levantado.</p>
 */
class MessagingLayerTest {

    /**
     * Mensaje literal tal y como lo pone {@code OutboxRabbitPublisher} en la cola: sobre
     * {@code AsynchronousMessage} con el {@code MasterDataChangedEvent} dentro. Está escrito a mano
     * y no generado para que un cambio de contrato en el emisor tenga que romper este texto.
     */
    private static final String PUBLISHED_MESSAGE_JSON = """
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
                "values": {
                  "code": "BCN-SANTS",
                  "name": "Barcelona Sants",
                  "kp": 3.75
                }
              },
              "messageHash": "9f2c1b0d5a8e7f6c4b3a2d1e0f9c8b7a6d5e4f3c2b1a0918273645546372819a"
            }""";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RabbitAutoConfiguration.class, JacksonAutoConfiguration.class))
            .withUserConfiguration(RabbitMqConfiguration.class, TestHandlerConfiguration.class)
            // Sin esto el contenedor de listeners arranca y se pone a reintentar la conexion contra
            // un broker que en un test no existe: hilos y ruido para no comprobar nada mas.
            .withPropertyValues("spring.rabbitmq.listener.simple.auto-startup=false");

    // ---------------------------------------------------------------------------------------
    // Contrato del mensaje
    // ---------------------------------------------------------------------------------------

    @Test
    void messagePublishedByConfigurationDeserializesIntoTheSharedContract() {
        MasterDataChangedMessage message = convert(PUBLISHED_MESSAGE_JSON);

        assertEquals(UUID.fromString("0f8b1f4c-3f6a-4a6d-9a2a-1c9f5f6f2b10"), message.operationId());
        assertEquals("station-42", message.referenceId());
        assertEquals("mto-configuration", message.origin());
        assertEquals(Instant.parse("2026-09-01T10:15:30Z"), message.creationDate());
        assertEquals("MASTER_DATA_STATION_UPDATED", message.eventType());

        MasterDataChangedEvent event = message.data();
        assertEquals("station", event.entityName());
        assertEquals("42", event.entityId());
        assertEquals(MasterDataOperation.UPDATED, event.operation());
        assertEquals("BCN-SANTS", event.values().get("code"));
    }

    /**
     * El emisor puede añadir campos al mensaje sin avisar a cada consumidor. Si eso rompiera la
     * deserialización, mensajes perfectamente válidos acabarían en la DLQ el día de un despliegue
     * de {@code mto-configuration}.
     */
    @Test
    void unknownFieldsFromANewerPublisherDoNotSendTheMessageToTheDeadLetterQueue() {
        String json = PUBLISHED_MESSAGE_JSON.replace(
                "\"referenceId\": \"station-42\"",
                "\"referenceId\": \"station-42\",\n  \"schemaVersion\": 2");

        MasterDataChangedMessage message = convert(json);

        assertEquals("station-42", message.referenceId());
        assertEquals(MasterDataOperation.UPDATED, message.data().operation());
    }

    @Test
    void routingValuesOfEachOperationMatchThePublisherRoutingKeys() {
        assertEquals("created", MasterDataOperation.CREATED.routingValue());
        assertEquals("updated", MasterDataOperation.UPDATED.routingValue());
        assertEquals("deleted", MasterDataOperation.DELETED.routingValue());
    }

    // ---------------------------------------------------------------------------------------
    // Listener y manejador
    // ---------------------------------------------------------------------------------------

    /**
     * El listener no puede llamar al manejador: entre uno y otro está el inbox, que es quien decide
     * si el trabajo llega a ejecutarse. Delegar en el procesador es lo que hace que un duplicado no
     * ejecute nada.
     */
    @Test
    void consumerDelegatesToTheIdempotentProcessorAndNotToTheHandler() {
        RecordingProcessor processor = new RecordingProcessor(InboxProcessingResult.PROCESSED);
        MasterDataChangedMessage message = convert(PUBLISHED_MESSAGE_JSON);

        new MasterDataEventConsumer(processor).onMasterDataChanged(message, rawMessage());

        assertEquals(1, processor.handled.size());
        assertSame(message, processor.handled.getFirst());
        assertEquals("0f8b1f4c-3f6a-4a6d-9a2a-1c9f5f6f2b10", processor.commands.getFirst().messageId());
    }

    /**
     * Una entrega repetida de un mensaje ya aplicado no es un fallo: el listener vuelve sin
     * excepción y el contenedor confirma. Si lanzara, el duplicado daría vueltas por la cola y
     * acabaría en la DLQ.
     */
    @Test
    void duplicateResultIsAcknowledgedInsteadOfRejected() {
        RecordingProcessor processor = new RecordingProcessor(InboxProcessingResult.DUPLICATE_SKIPPED);
        MasterDataChangedMessage message = convert(PUBLISHED_MESSAGE_JSON);

        assertDoesNotThrow(() -> new MasterDataEventConsumer(processor).onMasterDataChanged(message, rawMessage()));
    }

    /**
     * Un sobre sin payload no mejora por reintentarlo: el mensaje que hay en la cola es el mismo.
     * Va directo a la DLQ sin gastar los intentos configurados y sin llegar al inbox.
     */
    @Test
    void messageWithoutPayloadIsRejectedWithoutRetryAndNeverReachesTheProcessor() {
        RecordingProcessor processor = new RecordingProcessor(InboxProcessingResult.PROCESSED);
        MasterDataChangedMessage withoutData = new MasterDataChangedMessage(
                UUID.randomUUID(), "station-42", "mto-configuration", Instant.now(),
                "MASTER_DATA_STATION_UPDATED", null, "hash");

        assertThrows(AmqpRejectAndDontRequeueException.class,
                () -> new MasterDataEventConsumer(processor).onMasterDataChanged(withoutData, rawMessage()));

        assertTrue(processor.handled.isEmpty());
    }

    /**
     * Sin saber qué entidad cambió no hay a quién enrutar el evento, y volver a leer el mismo
     * mensaje no va a añadirle el dato: va a la DLQ sin gastar reintentos.
     */
    @Test
    void messageWithoutEntityNameOrOperationIsRejectedWithoutRetry() {
        RecordingProcessor processor = new RecordingProcessor(InboxProcessingResult.PROCESSED);
        MasterDataChangedMessage withoutEntityName = messageWith(
                new MasterDataChangedEvent("  ", "42", MasterDataOperation.UPDATED, Map.of()));
        MasterDataChangedMessage withoutOperation = messageWith(
                new MasterDataChangedEvent("station", "42", null, Map.of()));

        assertThrows(AmqpRejectAndDontRequeueException.class,
                () -> new MasterDataEventConsumer(processor).onMasterDataChanged(withoutEntityName, rawMessage()));
        assertThrows(AmqpRejectAndDontRequeueException.class,
                () -> new MasterDataEventConsumer(processor).onMasterDataChanged(withoutOperation, rawMessage()));

        assertTrue(processor.handled.isEmpty());
    }

    /**
     * Si el listener se tragara el fallo, el contenedor confirmaría el mensaje como procesado: el
     * evento se perdería y la DLQ quedaría vacía, que es justo lo que hace creer que todo va bien.
     */
    @Test
    void processorFailuresPropagateSoTheContainerCanRetryAndThenDeadLetter() {
        MasterDataEventProcessor failing = (command, message) -> {
            throw new IllegalStateException("database is down");
        };
        MasterDataChangedMessage message = convert(PUBLISHED_MESSAGE_JSON);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new MasterDataEventConsumer(failing).onMasterDataChanged(message, rawMessage()));

        assertEquals("database is down", exception.getMessage());
    }

    // ---------------------------------------------------------------------------------------
    // Clave de idempotencia y comando del inbox
    // ---------------------------------------------------------------------------------------

    /**
     * El {@code operationId} identifica la operación que generó el evento en origen y viaja dentro
     * del payload que el outbox guardó una sola vez: es el mismo en cada reentrega.
     */
    @Test
    void idempotencyKeyComesFromTheOperationIdOfTheEnvelope() {
        InboxMessageCommand command =
                InboxMessageCommandFactory.from(convert(PUBLISHED_MESSAGE_JSON), rawMessage());

        assertEquals("0f8b1f4c-3f6a-4a6d-9a2a-1c9f5f6f2b10", command.messageId());
        assertEquals("mto-configuration", command.sourceService());
        assertEquals("MASTER_DATA_STATION_UPDATED", command.eventType());
        assertEquals("station", command.aggregateType());
        assertEquals("42", command.aggregateId());
        assertEquals(MasterDataRabbitMqNames.MASTER_DATA_EXCHANGE, command.exchangeName());
        assertEquals("mto.master-data.station.updated", command.routingKey());
        assertEquals(MasterDataRabbitMqNames.STOCK_MASTER_DATA_QUEUE, command.queueName());
    }

    /** Red por si el contrato del payload cambiase: el message_id de AMQP es igual de estable. */
    @Test
    void idempotencyKeyFallsBackToTheAmqpMessageIdWhenTheEnvelopeHasNoOperationId() {
        MasterDataChangedMessage withoutOperationId = new MasterDataChangedMessage(
                null, "station-42", "mto-configuration", Instant.now(), "MASTER_DATA_STATION_UPDATED",
                new MasterDataChangedEvent("station", "42", MasterDataOperation.UPDATED, Map.of()), "hash");

        InboxMessageCommand command = InboxMessageCommandFactory.from(withoutOperationId, rawMessage());

        assertEquals("f4b0a1c2-0000-4000-8000-000000000001", command.messageId());
    }

    /**
     * Aplicarlo «de todas formas» sería peor que descartarlo: sin identificador estable no hay forma
     * de reconocer la siguiente entrega del mismo evento, y la promesa de exactamente-una-vez se
     * rompería en silencio justo cuando alguien ya cuenta con ella.
     */
    @Test
    void messageWithoutAnyStableIdentifierIsRejectedWithoutRetry() {
        MasterDataChangedMessage withoutOperationId = new MasterDataChangedMessage(
                null, "station-42", "mto-configuration", Instant.now(), "MASTER_DATA_STATION_UPDATED",
                new MasterDataChangedEvent("station", "42", MasterDataOperation.UPDATED, Map.of()), "hash");
        MessageProperties withoutMessageId = new MessageProperties();
        Message raw = MessageBuilder.withBody(new byte[]{'{', '}'}).andProperties(withoutMessageId).build();

        assertThrows(AmqpRejectAndDontRequeueException.class,
                () -> InboxMessageCommandFactory.from(withoutOperationId, raw));
    }

    /**
     * Se guardan los bytes recibidos, no el DTO reserializado: la ida y vuelta no conserva la
     * identidad -un 1.50 vuelve como 1.5- y dejaría almacenado algo que no es lo que envió el
     * emisor.
     */
    @Test
    void inboxStoresTheOriginalPayloadBytesAndTheirHash() {
        InboxMessageCommand command =
                InboxMessageCommandFactory.from(convert(PUBLISHED_MESSAGE_JSON), rawMessage(PUBLISHED_MESSAGE_JSON));

        assertEquals(PUBLISHED_MESSAGE_JSON, command.payload());
        assertNotNull(command.payloadHash());
        assertEquals(64, command.payloadHash().length());
    }

    /**
     * El emisor la escribe como long, pero AMQP devuelve el entero mas pequeno en el que quepa: un
     * numero por debajo de Integer.MAX_VALUE llega como Integer, que es el caso normal.
     */
    @Test
    void sequenceNumberIsReadFromTheHeaderWhateverIntegerTypeAmqpUsed() {
        MasterDataChangedMessage message = convert(PUBLISHED_MESSAGE_JSON);

        assertEquals(7L, InboxMessageCommandFactory.from(message, rawMessage()).sequenceNumber());
        assertEquals(7L, InboxMessageCommandFactory.from(message, rawMessageWithSequence(7)).sequenceNumber());
        assertEquals(7L, InboxMessageCommandFactory.from(message, rawMessageWithSequence("7")).sequenceNumber());
    }

    /**
     * Sin ella no se puede ordenar, pero si aplicar: rechazar el mensaje dejaria este servicio sin
     * consumir nada si el emisor dejara de enviarla.
     */
    @Test
    void aMissingOrUnreadableSequenceNumberDoesNotRejectTheMessage() {
        MasterDataChangedMessage message = convert(PUBLISHED_MESSAGE_JSON);

        assertNull(InboxMessageCommandFactory.from(message, rawMessageWithSequence(null)).sequenceNumber());
        assertNull(InboxMessageCommandFactory.from(message, rawMessageWithSequence("no soy un numero"))
                .sequenceNumber());
    }

    /** El emisor es procedencia, no clave: su ausencia no puede tirar un mensaje identificable. */
    @Test
    void missingOriginIsRecordedAsUnknownInsteadOfRejectingTheMessage() {
        MasterDataChangedMessage withoutOrigin = new MasterDataChangedMessage(
                UUID.randomUUID(), "station-42", "  ", Instant.now(), "MASTER_DATA_STATION_UPDATED",
                new MasterDataChangedEvent("station", "42", MasterDataOperation.UPDATED, Map.of()), "hash");

        InboxMessageCommand command = InboxMessageCommandFactory.from(withoutOrigin, rawMessage());

        assertEquals("unknown", command.sourceService());
    }

    // ---------------------------------------------------------------------------------------
    // Properties
    // ---------------------------------------------------------------------------------------

    /**
     * Una variable de entorno declarada y vacía es el caso normal de un despliegue a medio
     * configurar. Arrancar contra un exchange llamado {@code ""} sería peor que arrancar contra el
     * contrato compartido.
     */
    @Test
    void blankPropertiesFallBackToTheSharedContract() {
        MasterDataRabbitProperties properties =
                new MasterDataRabbitProperties(null, "", "  ", null, "", null);

        assertEquals(MasterDataRabbitMqNames.MASTER_DATA_EXCHANGE, properties.exchange());
        assertEquals(MasterDataRabbitMqNames.STOCK_MASTER_DATA_QUEUE, properties.queue());
        assertEquals(MasterDataRabbitMqNames.MASTER_DATA_ROUTING_PATTERN, properties.routingKey());
        assertEquals(MasterDataRabbitMqNames.STOCK_MASTER_DATA_DEAD_LETTER_EXCHANGE, properties.deadLetterExchange());
        assertEquals(MasterDataRabbitMqNames.STOCK_MASTER_DATA_DEAD_LETTER_QUEUE, properties.deadLetterQueue());
        assertEquals(MasterDataRabbitMqNames.STOCK_MASTER_DATA_DEAD_LETTER_ROUTING_KEY,
                properties.deadLetterRoutingKey());
    }

    @Test
    void propertiesOverrideTheContractNamesWhenAnEnvironmentNeedsIt() {
        contextRunner
                .withPropertyValues("app.rabbitmq.master-data.queue=mto.stock.master-data.staging.queue")
                .run(context -> assertEquals("mto.stock.master-data.staging.queue",
                        context.getBean(MasterDataRabbitProperties.class).queue()));
    }

    // ---------------------------------------------------------------------------------------
    // Topología y cableado
    // ---------------------------------------------------------------------------------------

    /**
     * El exchange se redeclara con los mismos atributos que usa {@code mto-configuration}. Si no
     * coincidieran, el broker respondería {@code PRECONDITION_FAILED} y se quedarían sin declarar
     * la cola y el binding: sin binding no llega ni un mensaje, y no hay error después del
     * arranque que lo delate.
     */
    @Test
    void topologyDeclaresTheConfigurationExchangeAndAQueueBoundToIt() {
        contextRunner.run(context -> {
            TopicExchange exchange = context.getBean(TopicExchange.class);
            assertEquals(MasterDataRabbitMqNames.MASTER_DATA_EXCHANGE, exchange.getName());
            assertTrue(exchange.isDurable());
            assertFalse(exchange.isAutoDelete());

            Queue queue = context.getBean("masterDataQueue", Queue.class);
            assertEquals(MasterDataRabbitMqNames.STOCK_MASTER_DATA_QUEUE, queue.getName());
            assertTrue(queue.isDurable());

            Binding binding = context.getBean("masterDataBinding", Binding.class);
            assertEquals(MasterDataRabbitMqNames.STOCK_MASTER_DATA_QUEUE, binding.getDestination());
            assertEquals(MasterDataRabbitMqNames.MASTER_DATA_EXCHANGE, binding.getExchange());
            assertEquals(MasterDataRabbitMqNames.MASTER_DATA_ROUTING_PATTERN, binding.getRoutingKey());
        });
    }

    @Test
    void queueDeadLettersToItsOwnExchangeAndQueue() {
        contextRunner.run(context -> {
            Queue queue = context.getBean("masterDataQueue", Queue.class);
            assertEquals(MasterDataRabbitMqNames.STOCK_MASTER_DATA_DEAD_LETTER_EXCHANGE,
                    queue.getArguments().get(MasterDataRabbitMqNames.ARG_DEAD_LETTER_EXCHANGE));
            assertEquals(MasterDataRabbitMqNames.STOCK_MASTER_DATA_DEAD_LETTER_ROUTING_KEY,
                    queue.getArguments().get(MasterDataRabbitMqNames.ARG_DEAD_LETTER_ROUTING_KEY));

            DirectExchange deadLetterExchange = context.getBean(DirectExchange.class);
            assertEquals(MasterDataRabbitMqNames.STOCK_MASTER_DATA_DEAD_LETTER_EXCHANGE, deadLetterExchange.getName());

            Queue deadLetterQueue = context.getBean("masterDataDeadLetterQueue", Queue.class);
            assertEquals(MasterDataRabbitMqNames.STOCK_MASTER_DATA_DEAD_LETTER_QUEUE, deadLetterQueue.getName());

            Binding deadLetterBinding = context.getBean("masterDataDeadLetterBinding", Binding.class);
            assertEquals(MasterDataRabbitMqNames.STOCK_MASTER_DATA_DEAD_LETTER_QUEUE,
                    deadLetterBinding.getDestination());
            assertEquals(MasterDataRabbitMqNames.STOCK_MASTER_DATA_DEAD_LETTER_EXCHANGE,
                    deadLetterBinding.getExchange());
            assertEquals(MasterDataRabbitMqNames.STOCK_MASTER_DATA_DEAD_LETTER_ROUTING_KEY,
                    deadLetterBinding.getRoutingKey());
        });
    }

    /**
     * Con el valor por defecto de Spring AMQP, un mensaje rechazado vuelve a la cabeza de la cola y
     * se reentrega sin fin: el consumidor gira en vacío y la DLQ nunca recibe nada.
     */
    @Test
    void listenerFactoryNeverRequeuesARejectedMessage() {
        contextRunner.run(context -> {
            SimpleRabbitListenerContainerFactory factory = context.getBean(
                    RabbitListenerContainerFactoryNames.MASTER_DATA, SimpleRabbitListenerContainerFactory.class);

            // El getter de la factory es protected: se lee el campo porque la alternativa seria no
            // comprobar la unica garantia que evita que el canal gire en vacio sobre un mensaje malo.
            assertEquals(Boolean.FALSE,
                    ReflectionTestUtils.getField(factory, "defaultRequeueRejected"));
        });
    }

    /**
     * Los beans de topología no se declaran solos: quien los recorre y los envía al broker es el
     * {@code RabbitAdmin} que autoconfigura Spring Boot. Sin él, la cola y el binding existirían
     * como beans y no como objetos del broker, y el servicio no recibiría nada.
     */
    @Test
    void topologyBeansAreDeclaredByTheAutoConfiguredAdmin() {
        contextRunner.run(context -> assertEquals(1, context.getBeansOfType(AmqpAdmin.class).size()));
    }

    @Test
    void consumerIsWiredByDefault() {
        contextRunner.run(context -> assertTrue(context.containsBean("masterDataEventConsumer")));
    }

    /**
     * Apagar el consumidor deja la topología declarada: la cola sigue acumulando eventos mientras
     * este servicio todavía no sabe qué hacer con ellos.
     */
    @Test
    void listenerCanBeDisabledWithoutRemovingTheTopology() {
        contextRunner
                .withPropertyValues("app.rabbitmq.master-data.listener-enabled=false")
                .run(context -> {
                    assertFalse(context.containsBean("masterDataEventConsumer"));
                    assertTrue(context.containsBean("masterDataQueue"));
                });
    }

    /** Sin estos beans nadie abre una conexión, así que la aplicación arranca sin broker. */
    @Test
    void rabbitChannelDisappearsEntirelyWhenDisabled() {
        contextRunner
                .withPropertyValues("app.rabbitmq.enabled=false")
                .run(context -> {
                    assertFalse(context.containsBean("masterDataEventConsumer"));
                    assertFalse(context.containsBean("masterDataQueue"));
                    assertFalse(context.containsBean("masterDataExchange"));
                    assertFalse(context.containsBean(RabbitListenerContainerFactoryNames.MASTER_DATA));
                });
    }

    // ---------------------------------------------------------------------------------------

    /**
     * Deserializa con el mismo convertidor que usa el contenedor. El emisor publica bytes ya
     * serializados y sin cabecera {@code __TypeId__}, asi que el tipo destino no viaja en el
     * mensaje: lo aporta el tipo inferido del metodo anotado, que es lo que se reproduce aqui.
     */
    private static MasterDataChangedMessage convert(String json) {
        MessageConverter converter = new RabbitMqConfiguration(
                new MasterDataRabbitProperties(null, null, null, null, null, null))
                .masterDataMessageConverter(JsonMapper.builder().build());

        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding(StandardCharsets.UTF_8.name());
        properties.setInferredArgumentType(MasterDataChangedMessage.class);

        Message message = MessageBuilder.withBody(json.getBytes(StandardCharsets.UTF_8))
                .andProperties(properties)
                .build();

        return (MasterDataChangedMessage) converter.fromMessage(message);
    }

    private static Message rawMessageWithSequence(Object sequenceNumber) {
        Message raw = rawMessage();
        raw.getMessageProperties().setHeader(MasterDataMessageHeaders.SEQUENCE_NUMBER, sequenceNumber);
        return raw;
    }

    private static MasterDataChangedMessage messageWith(MasterDataChangedEvent event) {
        return new MasterDataChangedMessage(UUID.randomUUID(), "station-42", "mto-configuration",
                Instant.now(), "MASTER_DATA_STATION_UPDATED", event, "hash");
    }

    private static Message rawMessage() {
        return rawMessage(PUBLISHED_MESSAGE_JSON);
    }

    private static Message rawMessage(String body) {
        MessageProperties properties = new MessageProperties();
        properties.setReceivedExchange(MasterDataRabbitMqNames.MASTER_DATA_EXCHANGE);
        properties.setReceivedRoutingKey("mto.master-data.station.updated");
        properties.setConsumerQueue(MasterDataRabbitMqNames.STOCK_MASTER_DATA_QUEUE);
        properties.setMessageId("f4b0a1c2-0000-4000-8000-000000000001");
        properties.setHeader(MasterDataMessageHeaders.EVENT_TYPE, "MASTER_DATA_STATION_UPDATED");
        properties.setHeader(MasterDataMessageHeaders.AGGREGATE_TYPE, "station");
        properties.setHeader(MasterDataMessageHeaders.AGGREGATE_ID, "42");
        properties.setHeader(MasterDataMessageHeaders.SEQUENCE_NUMBER, 7L);
        properties.setHeader(MasterDataMessageHeaders.SIGNATURE_ALGORITHM, "SHA-256");

        return MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8)).andProperties(properties).build();
    }

    private static final class RecordingProcessor implements MasterDataEventProcessor {

        private final List<MasterDataChangedMessage> handled = new ArrayList<>();
        private final List<InboxMessageCommand> commands = new ArrayList<>();
        private final InboxProcessingResult result;

        private RecordingProcessor(InboxProcessingResult result) {
            this.result = result;
        }

        @Override
        public InboxProcessingResult process(InboxMessageCommand command, MasterDataChangedMessage message) {
            commands.add(command);
            handled.add(message);
            return result;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TestHandlerConfiguration {

        @Bean
        MasterDataEventProcessor masterDataEventProcessor() {
            return new RecordingProcessor(InboxProcessingResult.PROCESSED);
        }
    }
}
