package com.alejandro.mtostock.infrastructure.messaging;

import com.alejandro.mtostock.application.dto.messaging.AsynchronousMessage;
import com.alejandro.mtostock.application.dto.messaging.DomainEvent;
import com.alejandro.mtostock.application.dto.messaging.MessageActor;
import com.alejandro.mtostock.application.dto.messaging.MessageActorKind;
import com.alejandro.mtostock.configuration.messaging.MessagePayloadSignature;
import com.alejandro.mtostock.configuration.rabbitmq.StockEventsProperties;
import com.alejandro.mtostock.configuration.security.CurrentUserService;
import com.alejandro.mtostock.configuration.security.JwtClaimNames;
import com.alejandro.mtostock.infrastructure.messaging.outbox.AsynchronousMessageFactory;
import com.alejandro.mtostock.infrastructure.messaging.outbox.AsynchronousMessageHashService;
import com.alejandro.mtostock.infrastructure.messaging.outbox.MessageContextResolver;
import com.alejandro.mtostock.infrastructure.messaging.outbox.OutboxDomainEventPublisher;
import com.alejandro.mtostock.infrastructure.messaging.outbox.OutboxService;
import com.alejandro.mtostock.infrastructure.messaging.rabbitmq.StockRabbitMqNames;
import com.alejandro.mtostock.infrastructure.persistence.audit.MessagingAuditContext;
import org.junit.jupiter.api.AfterEach;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.alejandro.mtostock.application.dto.messaging.MasterDataChangedEvent;
import com.alejandro.mtostock.application.dto.messaging.MasterDataChangedMessage;
import com.alejandro.mtostock.application.dto.messaging.MasterDataOperation;
import com.alejandro.mtostock.application.dto.messaging.InboxMessageCommand;
import com.alejandro.mtostock.application.dto.messaging.InboxProcessingResult;
import com.alejandro.mtostock.application.service.MasterDataEventProcessor;
import com.alejandro.mtostock.configuration.messaging.MessagePayloadSignatureVerifier;
import com.alejandro.mtostock.configuration.messaging.MessagingConfiguration;
import com.alejandro.mtostock.configuration.messaging.MessageSignatureMode;
import com.alejandro.mtostock.configuration.messaging.MessageSignatureProperties;
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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
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

    private static final String SECRET = "un-secreto-compartido";

    /** Los tests que no van de firmas no la comprueban: cada uno prueba una cosa. */
    private static final MessagePayloadSignatureVerifier NO_SIGNATURE_CHECK =
            new MessagePayloadSignatureVerifier(
                    new MessageSignatureProperties(null, MessageSignatureMode.DISABLED));

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RabbitAutoConfiguration.class, JacksonAutoConfiguration.class))
            .withUserConfiguration(RabbitMqConfiguration.class, MessagingConfiguration.class,
                    TestHandlerConfiguration.class)
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

        new MasterDataEventConsumer(processor, NO_SIGNATURE_CHECK).onMasterDataChanged(message, rawMessage());

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

        assertDoesNotThrow(() -> new MasterDataEventConsumer(processor, NO_SIGNATURE_CHECK).onMasterDataChanged(message, rawMessage()));
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
                () -> new MasterDataEventConsumer(processor, NO_SIGNATURE_CHECK).onMasterDataChanged(withoutData, rawMessage()));

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
                () -> new MasterDataEventConsumer(processor, NO_SIGNATURE_CHECK).onMasterDataChanged(withoutEntityName, rawMessage()));
        assertThrows(AmqpRejectAndDontRequeueException.class,
                () -> new MasterDataEventConsumer(processor, NO_SIGNATURE_CHECK).onMasterDataChanged(withoutOperation, rawMessage()));

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
                () -> new MasterDataEventConsumer(failing, NO_SIGNATURE_CHECK).onMasterDataChanged(message, rawMessage()));

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
    // Firma del mensaje
    // ---------------------------------------------------------------------------------------

    /**
     * Se firma igual que en el emisor —HMAC-SHA256 sobre los bytes que viajan, en hexadecimal— para
     * que lo que se comprueba aqui sea el contrato y no una reimplementacion de si mismo.
     */
    @Test
    void aMessageSignedWithTheSharedSecretIsAccepted() {
        MessagePayloadSignatureVerifier verifier = verifier(SECRET, MessageSignatureMode.REQUIRED);
        byte[] body = PUBLISHED_MESSAGE_JSON.getBytes(StandardCharsets.UTF_8);

        assertTrue(verifier.rejectionReason(body, hmac(SECRET, body), "HMAC-SHA256").isEmpty());
    }

    /** Un byte cambiado por el camino ya no cuadra, y eso no depende del modo. */
    @Test
    void aTamperedPayloadIsRejectedEvenWhenSignaturesAreOptional() {
        MessagePayloadSignatureVerifier verifier = verifier(SECRET, MessageSignatureMode.OPTIONAL);
        byte[] original = PUBLISHED_MESSAGE_JSON.getBytes(StandardCharsets.UTF_8);
        byte[] tampered = PUBLISHED_MESSAGE_JSON.replace("Barcelona Sants", "Barcelona Nord")
                .getBytes(StandardCharsets.UTF_8);

        Optional<String> rejection = verifier.rejectionReason(tampered, hmac(SECRET, original), "HMAC-SHA256");

        assertTrue(rejection.isPresent());
        assertTrue(rejection.get().contains("does not match"));
    }

    /** Sin secreto el emisor firma con SHA-256 simple, y eso si se puede recalcular aqui. */
    @Test
    void aMessageSignedWithoutASharedSecretIsVerifiedWithPlainSha256() {
        MessagePayloadSignatureVerifier verifier = verifier("", MessageSignatureMode.REQUIRED);
        byte[] body = PUBLISHED_MESSAGE_JSON.getBytes(StandardCharsets.UTF_8);

        assertTrue(verifier.rejectionReason(body, sha256(body), "SHA-256").isEmpty());
    }

    /**
     * El desastre a evitar: el emisor firma con HMAC porque tiene secreto y aqui no lo hay, con lo
     * que ninguna comparacion cuadra. Eso no es un mensaje manipulado, es configuracion a medias, y
     * tratarlo como lo primero mandaria TODOS los mensajes validos a la DLQ.
     */
    @Test
    void anAlgorithmThisServiceCannotComputeIsAcceptedWhenOptionalAndRejectedWhenRequired() {
        byte[] body = PUBLISHED_MESSAGE_JSON.getBytes(StandardCharsets.UTF_8);
        String signature = hmac(SECRET, body);

        assertTrue(verifier("", MessageSignatureMode.OPTIONAL)
                .rejectionReason(body, signature, "HMAC-SHA256").isEmpty());

        Optional<String> rejection = verifier("", MessageSignatureMode.REQUIRED)
                .rejectionReason(body, signature, "HMAC-SHA256");
        assertTrue(rejection.isPresent());
        assertTrue(rejection.get().contains("app.messaging.signature.secret"));
    }

    /** Un mensaje publicado a mano para probar no tiene por que tumbar el consumo. */
    @Test
    void anUnsignedMessageIsAcceptedWhenOptionalAndRejectedWhenRequired() {
        byte[] body = PUBLISHED_MESSAGE_JSON.getBytes(StandardCharsets.UTF_8);

        assertTrue(verifier(SECRET, MessageSignatureMode.OPTIONAL)
                .rejectionReason(body, null, null).isEmpty());
        assertTrue(verifier(SECRET, MessageSignatureMode.REQUIRED)
                .rejectionReason(body, null, null).isPresent());
        assertTrue(verifier(SECRET, MessageSignatureMode.DISABLED)
                .rejectionReason(body, null, null).isEmpty());
    }

    /** Con la comprobacion apagada no se mira nada, ni siquiera una firma que no cuadra. */
    @Test
    void nothingIsCheckedWhenVerificationIsDisabled() {
        byte[] body = PUBLISHED_MESSAGE_JSON.getBytes(StandardCharsets.UTF_8);

        assertTrue(verifier(SECRET, MessageSignatureMode.DISABLED)
                .rejectionReason(body, "una firma que no vale nada", "HMAC-SHA256").isEmpty());
    }

    /** El secreto no puede acabar en un log ni dentro del mensaje de una excepcion. */
    @Test
    void thePropertiesNeverPrintTheSecret() {
        String printed = new MessageSignatureProperties(SECRET, MessageSignatureMode.REQUIRED).toString();

        assertFalse(printed.contains(SECRET));
        assertTrue(printed.contains("***"));
    }

    /** El listener rechaza sin reintentos: los mismos bytes no van a empezar a cuadrar. */
    @Test
    void aMessageThatFailsVerificationGoesToTheDeadLetterQueueWithoutReachingTheProcessor() {
        RecordingProcessor processor = new RecordingProcessor(InboxProcessingResult.PROCESSED);
        MasterDataEventConsumer consumer = new MasterDataEventConsumer(
                processor, verifier(SECRET, MessageSignatureMode.REQUIRED));
        Message raw = rawMessage();
        raw.getMessageProperties().setHeader(MasterDataMessageHeaders.SIGNATURE, "no es la firma");
        raw.getMessageProperties().setHeader(MasterDataMessageHeaders.SIGNATURE_ALGORITHM, "HMAC-SHA256");

        assertThrows(AmqpRejectAndDontRequeueException.class,
                () -> consumer.onMasterDataChanged(convert(PUBLISHED_MESSAGE_JSON), raw));

        assertTrue(processor.handled.isEmpty());
    }

    private static MessagePayloadSignatureVerifier verifier(String secret, MessageSignatureMode mode) {
        return new MessagePayloadSignatureVerifier(new MessageSignatureProperties(secret, mode));
    }

    private static String hmac(String secret, byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String sha256(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
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
            TopicExchange exchange = context.getBean("masterDataExchange", TopicExchange.class);
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
                new MasterDataRabbitProperties(null, null, null, null, null, null), new StockEventsProperties(null))
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

    // ---------------------------------------------------------------------------------------
    // Eventos propios: exchange, nombres, sobre, contexto, firma y outbox (docs/06-messaging.md)
    // ---------------------------------------------------------------------------------------

    private static final String SUBJECT = "6f1b1c8e-0000-4000-8000-000000000041";

    private static final AsynchronousMessageHashService HASH_SERVICE = new AsynchronousMessageHashService(new ObjectMapper());

    private static final MessageContextResolver RESOLVER = new MessageContextResolver(new CurrentUserService());

    @AfterEach
    void clearTheContexts() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        MessagingAuditContext.clear();
    }

    /** El exchange propio se declara junto al de datos maestros; la cola que lo escucha es de mto-notification. */
    @Test
    void theOwnExchangeIsDeclaredAndNoQueueIsBoundToIt() {
        contextRunner.run(context -> {
            TopicExchange own = context.getBean("stockExchange", TopicExchange.class);
            assertEquals(StockRabbitMqNames.STOCK_EXCHANGE, own.getName());
            assertTrue(own.isDurable());
            assertFalse(own.isAutoDelete());
            assertTrue(context.getBeansOfType(Binding.class).values().stream()
                            .noneMatch(binding -> own.getName().equals(binding.getExchange())),
                    "A queue belongs to whoever consumes it: this service binds nothing to its own exchange");
        });
    }

    @Test
    void theOwnExchangeNameCanBeOverriddenAndABlankOneFallsBackToTheContract() {
        contextRunner
                .withPropertyValues("app.rabbitmq.events.exchange=mto.stock.staging.exchange")
                .run(context -> assertEquals("mto.stock.staging.exchange",
                        context.getBean("stockExchange", TopicExchange.class).getName()));

        assertEquals(StockRabbitMqNames.STOCK_EXCHANGE, new StockEventsProperties("  ").exchange());
    }

    /** mto-notification deriva el tipo de actividad de la clave: el formato no es cosmetico. */
    @Test
    void routingKeysAndEventTypesFollowTheContract() {
        assertEquals("mto.stock.material.below-minimum", StockRabbitMqNames.routingKey("material", "below-minimum"));
        assertEquals("STOCK_MATERIAL_BELOW_MINIMUM", StockRabbitMqNames.eventType("material", "below-minimum"));
        assertEquals("mto.stock.reservation.cancelled", StockRabbitMqNames.routingKey("Reservation", "Cancelled"));
        assertEquals("STOCK_RESERVATION_CANCELLED", StockRabbitMqNames.eventType("Reservation", "Cancelled"));
        assertEquals("STOCK_ADJUSTMENT_REGISTERED", StockRabbitMqNames.eventType("adjustment", "registered"));
        assertTrue(StockRabbitMqNames.routingKey("material", "below-minimum").startsWith(StockRabbitMqNames.STOCK_ROUTING_PREFIX + "."));
        assertFalse(StockRabbitMqNames.routingKey("material", "below-minimum").startsWith("mto.master-data"));
        assertEquals("mto.stock.#", StockRabbitMqNames.STOCK_ROUTING_PATTERN);
    }

    @Test
    void theEnvelopeCarriesTheOriginTheActorAndTheCorrelationOfTheContextItIsCreatedIn() {
        MessageContextResolver resolver = mock(MessageContextResolver.class);
        MessageActor actor = MessageActor.of(SUBJECT, "almacen.operario");
        when(resolver.currentActor()).thenReturn(actor);
        when(resolver.currentCorrelationId()).thenReturn("8c3b8c1a-1111-4222-8333-444444444444");
        AsynchronousMessageFactory factory = new AsynchronousMessageFactory(HASH_SERVICE, resolver, "mto-stock");

        AsynchronousMessage<Map<String, Object>> message = factory.create("reservation-1", "STOCK_RESERVATION_CANCELLED", Map.of("materialCode", "GA70"));

        assertEquals("mto-stock", message.origin());
        assertEquals(actor, message.actor());
        assertEquals("8c3b8c1a-1111-4222-8333-444444444444", message.correlationId());
        assertNotNull(message.operationId());
        assertTrue(message.messageHash().matches("[0-9a-f]{64}"));

        UUID given = UUID.fromString("00000000-0000-4000-8000-000000000042");
        assertEquals(given, factory.create(given, "material-1", "STOCK_MATERIAL_BELOW_MINIMUM", Map.of()).operationId(),
                "A given operationId is kept: it is the idempotency key at the consumer");
    }

    /** Un consumidor que ya calculaba la huella sobre las siete claves sigue obteniendo la misma. */
    @Test
    void theHashCoversTheSevenOriginalKeysOnlyAndIgnoresActorAndCorrelation() {
        MessageContextResolver resolver = mock(MessageContextResolver.class);
        when(resolver.currentActor()).thenReturn(MessageActor.of(SUBJECT, "ana.perez"));
        when(resolver.currentCorrelationId()).thenReturn("corr");
        AsynchronousMessage<Map<String, Object>> withContext = new AsynchronousMessageFactory(HASH_SERVICE, resolver, "mto-stock")
                .create("reservation-1", "STOCK_RESERVATION_CANCELLED", Map.of("materialCode", "GA70"));
        AsynchronousMessage<Map<String, Object>> withoutContext = new AsynchronousMessage<>(withContext.operationId(), withContext.referenceId(),
                withContext.origin(), withContext.creationDate(), withContext.eventType(), withContext.data(), "PENDING", null, null);

        assertEquals(withContext.messageHash(), HASH_SERVICE.calculate(withoutContext));
    }

    @Test
    void withoutAnAuthenticatedUserTheActorIsTheSystemSaidExplicitly() {
        assertEquals(MessageActor.system(), RESOLVER.currentActor());
        assertEquals(MessageActorKind.SYSTEM, RESOLVER.currentActor().kind());
        assertNull(RESOLVER.currentActor().username());
    }

    /** Lo clasifica el emisor porque solo el tiene el token delante: Keycloak nombra service-account-<cliente> a las cuentas de servicio. */
    @Test
    void aPersonWithAJwtIsAPersonAndAServiceAccountIsAService() {
        authenticateWithJwt("almacen.operario");
        assertEquals(new MessageActor(SUBJECT, "almacen.operario", MessageActorKind.PERSON), RESOLVER.currentActor());

        authenticateWithJwt("service-account-mto-maintenance-svc");
        assertEquals(new MessageActor(SUBJECT, "service-account-mto-maintenance-svc", MessageActorKind.SERVICE), RESOLVER.currentActor());
    }

    @Test
    void anAuthenticationThatIsNotAJwtIsAPersonWithTheirNameAndNoId() {
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(new UsernamePasswordAuthenticationToken(
                "ana.perez", "n/a", AuthorityUtils.createAuthorityList("ROLE_STOCK_READ")));
        SecurityContextHolder.setContext(securityContext);

        assertEquals(new MessageActor(null, "ana.perez", MessageActorKind.PERSON), RESOLVER.currentActor());
    }

    /** El mismo valor que guarda la revision de Envers: la cabecera de la peticion, o el mensaje de datos maestros en curso. */
    @Test
    void theCorrelationIdIsTheRequestHeaderFirstThenTheMasterDataMessageBeingProcessed() {
        assertNull(RESOLVER.currentCorrelationId(), "Outside a request and a message there is none, and it is said with null");

        MessagingAuditContext.set(new MessagingAuditContext.Context("0f8b1f4c-3f6a-4a6d-9a2a-1c9f5f6f2b10", "mto-configuration"));
        assertEquals("0f8b1f4c-3f6a-4a6d-9a2a-1c9f5f6f2b10", RESOLVER.currentCorrelationId());

        withRequestHeader("8c3b8c1a-1111-4222-8333-444444444444");
        assertEquals("8c3b8c1a-1111-4222-8333-444444444444", RESOLVER.currentCorrelationId(),
                "A request in course is the strongest evidence, even if a reused thread still carries a messaging context");
    }

    @Test
    void aCorrelationHeaderThatIsBlankTooLongOrNotPrintableCountsAsAbsent() {
        withRequestHeader("   ");
        assertNull(RESOLVER.currentCorrelationId());
        withRequestHeader("x".repeat(201));
        assertNull(RESOLVER.currentCorrelationId());
        withRequestHeader("con espacios");
        assertNull(RESOLVER.currentCorrelationId());
        withRequestHeader("  req-42  ");
        assertEquals("req-42", RESOLVER.currentCorrelationId(), "Trimmed, as the audit stores it");
    }

    /** Lo que este servicio firma lo acepta su propio verificador, que es el que corre en mto-notification con el mismo secreto. */
    @Test
    void whatThisServiceSignsIsWhatTheVerifierAccepts() {
        byte[] payload = "{\"data\":{\"values\":{\"quantity\":4.000000}}}".getBytes(StandardCharsets.UTF_8);
        MessageSignatureProperties withSecret = new MessageSignatureProperties(SECRET, MessageSignatureMode.REQUIRED);
        MessagePayloadSignature signer = new MessagePayloadSignature(withSecret);

        assertEquals("HMAC-SHA256", signer.algorithm());
        assertEquals(Optional.empty(), new MessagePayloadSignatureVerifier(withSecret)
                .rejectionReason(payload, signer.sign(payload), signer.algorithm()));
        assertTrue(new MessagePayloadSignatureVerifier(withSecret)
                .rejectionReason("{\"data\":{}}".getBytes(StandardCharsets.UTF_8), signer.sign(payload), signer.algorithm()).isPresent());

        MessagePayloadSignature plain = new MessagePayloadSignature(new MessageSignatureProperties("", MessageSignatureMode.REQUIRED));
        assertEquals("SHA-256", plain.algorithm());
        assertEquals(sha256Hex(payload), plain.sign(payload));
        assertTrue(plain.verify(payload, plain.sign(payload)));
        assertFalse(signer.verify(payload, plain.sign(payload)), "Another secret, another signature");
        assertThrows(IllegalArgumentException.class, () -> signer.sign((byte[]) null));
    }

    /** Ojo con lo que traen los eventos: una clave que huela a credencial no llega al outbox. */
    @Test
    void aDomainEventNeverCarriesAnythingThatSmellsLikeASecretAndKeepsItsKeysInOrder() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("apiKey", "x");
        assertThrows(IllegalArgumentException.class, () -> new DomainEvent("reservation", "1", "cancelled", Map.of("password", "x")));
        assertThrows(IllegalArgumentException.class, () -> new DomainEvent("reservation", "1", "cancelled", Map.of("project", nested)));
        assertThrows(IllegalArgumentException.class, () -> new DomainEvent("reservation", "1", "cancelled", Map.of("items", List.of(Map.of("secretCode", "x")))));
        assertThrows(IllegalArgumentException.class, () -> new DomainEvent(" ", "1", "cancelled", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new DomainEvent("reservation", null, "cancelled", Map.of()));

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("projectId", null);
        values.put("materialCode", "GA70");
        DomainEvent event = new DomainEvent("reservation", "1", "cancelled", values);

        assertEquals(List.of("projectId", "materialCode"), List.copyOf(event.values().keySet()), "Nulls travel and the order of the keys is kept");
        assertThrows(UnsupportedOperationException.class, () -> event.values().put("x", 1));
        assertEquals(Map.of(), new DomainEvent("reservation", "1", "cancelled", null).values());
    }

    @Test
    void theOutboxPublisherWritesTheEnvelopeUnderTheContractNames() {
        MessageContextResolver resolver = mock(MessageContextResolver.class);
        when(resolver.currentActor()).thenReturn(MessageActor.system());
        OutboxService outboxService = mock(OutboxService.class);
        OutboxDomainEventPublisher publisher = new OutboxDomainEventPublisher(
                new AsynchronousMessageFactory(HASH_SERVICE, resolver, "mto-stock"), outboxService, new StockEventsProperties(null));
        DomainEvent event = new DomainEvent("reservation", "7", "cancelled", Map.of("materialCode", "GA70", "status", "CANCELLED"));
        UUID operationId = UUID.fromString("00000000-0000-4000-8000-000000000007");

        publisher.publish(operationId, event);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<AsynchronousMessage<DomainEvent>> captor = ArgumentCaptor.forClass(AsynchronousMessage.class);
        verify(outboxService).save(eq("reservation"), eq("7"), eq("STOCK_RESERVATION_CANCELLED"),
                eq("mto.stock.exchange"), eq("mto.stock.reservation.cancelled"), captor.capture());
        AsynchronousMessage<DomainEvent> message = captor.getValue();
        assertEquals(operationId, message.operationId());
        assertEquals("reservation-7", message.referenceId(), "The aggregate of the outbox: the events of one reservation, in order");
        assertEquals("STOCK_RESERVATION_CANCELLED", message.eventType());
        assertSame(event, message.data());
        assertEquals(MessageActorKind.SYSTEM, message.actor().kind());
        assertTrue(publisher.isEnabled());
    }

    private static void authenticateWithJwt(String username) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(SUBJECT)
                .claim(JwtClaimNames.PREFERRED_USERNAME, username)
                .build();
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(new JwtAuthenticationToken(jwt, AuthorityUtils.createAuthorityList("ROLE_STOCK_READ"), username));
        SecurityContextHolder.setContext(securityContext);
    }

    private static void withRequestHeader(String value) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Correlation-Id", value);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private static String sha256Hex(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
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
