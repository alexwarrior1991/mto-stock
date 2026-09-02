package com.alejandro.mtostock.infrastructure.messaging.rabbitmq;

import com.alejandro.mtostock.application.dto.messaging.InboxMessageCommand;
import com.alejandro.mtostock.application.dto.messaging.MasterDataChangedEvent;
import com.alejandro.mtostock.application.dto.messaging.MasterDataChangedMessage;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Turns an AMQP delivery into the transport-free command the inbox works with.
 *
 * <p>Aquí acaba todo lo que sabe de RabbitMQ. El listener queda delgado y el servicio de inbox no
 * ve una cabecera en su vida, así que el mismo evento llegado por otro canal reutilizaría el
 * inbox tal cual.</p>
 *
 * <h2>De dónde sale la clave de idempotencia</h2>
 *
 * <p>Por orden: el {@code operationId} del sobre y, si faltase, el {@code message_id} de AMQP. El
 * primero identifica la operación que generó el evento en {@code mto-configuration} y es el mismo
 * en cada reentrega, porque viaja dentro del payload que el outbox guardó una sola vez. El segundo
 * es el identificador de la fila de ese outbox, igual de estable, y sirve de red por si el contrato
 * del payload cambiase.</p>
 *
 * <p>Sin ninguno de los dos el mensaje se rechaza sin reintentos. Aplicarlo «de todas formas» sería
 * peor que descartarlo: sin identificador estable no hay forma de reconocer la siguiente entrega
 * del mismo evento, y la promesa de exactamente-una-vez se rompe en silencio justo cuando alguien
 * ya cuenta con ella.</p>
 */
public final class InboxMessageCommandFactory {

    /**
     * Se registra el emisor cuando el sobre no lo trae, en vez de rechazar el mensaje: es
     * información de procedencia, no la clave. Al formar parte de la restricción única, un valor
     * fijo mantiene la unicidad entre entregas del mismo mensaje, que es lo que importa.
     */
    static final String UNKNOWN_SOURCE_SERVICE = "unknown";

    private static final int MAX_MESSAGE_ID_LENGTH = 200;
    private static final int MAX_SOURCE_SERVICE_LENGTH = 100;
    private static final int MAX_EVENT_TYPE_LENGTH = 150;
    private static final int MAX_AGGREGATE_TYPE_LENGTH = 150;
    private static final int MAX_AGGREGATE_ID_LENGTH = 100;
    private static final int MAX_ROUTING_LENGTH = 255;

    private InboxMessageCommandFactory() {
    }

    public static InboxMessageCommand from(MasterDataChangedMessage message, Message rawMessage) {
        MessageProperties properties = rawMessage.getMessageProperties();
        String payload = new String(rawMessage.getBody(), StandardCharsets.UTF_8);

        return new InboxMessageCommand(
                idempotencyKey(message, properties),
                truncate(sourceService(message), MAX_SOURCE_SERVICE_LENGTH),
                truncate(message == null ? null : message.eventType(), MAX_EVENT_TYPE_LENGTH),
                truncate(aggregateType(message), MAX_AGGREGATE_TYPE_LENGTH),
                truncate(aggregateId(message), MAX_AGGREGATE_ID_LENGTH),
                truncate(properties.getReceivedExchange(), MAX_ROUTING_LENGTH),
                truncate(properties.getReceivedRoutingKey(), MAX_ROUTING_LENGTH),
                truncate(properties.getConsumerQueue(), MAX_ROUTING_LENGTH),
                sha256(payload),
                payload);
    }

    private static String idempotencyKey(MasterDataChangedMessage message, MessageProperties properties) {
        String key = message != null && message.operationId() != null
                ? message.operationId().toString()
                : properties.getMessageId();

        if (key == null || key.isBlank()) {
            throw new AmqpRejectAndDontRequeueException(
                    "Message has neither an operationId in the payload nor an AMQP messageId: without a "
                            + "stable identifier the inbox cannot guarantee it is applied exactly once");
        }

        // No se recorta: dos identificadores distintos que se recortasen al mismo valor harían que
        // un evento legítimo se descartase como duplicado, que es una pérdida silenciosa.
        if (key.length() > MAX_MESSAGE_ID_LENGTH) {
            throw new AmqpRejectAndDontRequeueException(
                    "Message identifier is longer than " + MAX_MESSAGE_ID_LENGTH + " characters: " + key.length());
        }

        return key;
    }

    private static String sourceService(MasterDataChangedMessage message) {
        if (message == null || message.origin() == null || message.origin().isBlank()) {
            return UNKNOWN_SOURCE_SERVICE;
        }
        return message.origin();
    }

    private static String aggregateType(MasterDataChangedMessage message) {
        MasterDataChangedEvent event = message == null ? null : message.data();
        return event == null ? null : event.entityName();
    }

    private static String aggregateId(MasterDataChangedMessage message) {
        MasterDataChangedEvent event = message == null ? null : message.data();
        return event == null ? null : event.entityId();
    }

    /**
     * Huella de los bytes recibidos, no del objeto deserializado: reserializar no conserva la
     * identidad —un 1.50 vuelve como 1.5— y daría una huella distinta para el mismo mensaje. Es un
     * dato auxiliar para correlacionar, nunca la clave de idempotencia.
     */
    private static String sha256(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
