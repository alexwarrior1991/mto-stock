package com.alejandro.mtostock.infrastructure.messaging.rabbitmq;

import com.alejandro.mtostock.application.dto.messaging.InboxMessageCommand;
import com.alejandro.mtostock.application.dto.messaging.InboxProcessingResult;
import com.alejandro.mtostock.application.dto.messaging.MasterDataChangedMessage;
import com.alejandro.mtostock.application.service.MasterDataEventProcessor;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

/**
 * Reads master data messages off the queue and hands them to the idempotent processor.
 *
 * <p>El listener es deliberadamente delgado: registra de dónde viene el mensaje, comprueba que el
 * sobre trae payload, traduce los metadatos de AMQP a un comando y delega. No persiste nada y no
 * decide nada de negocio — la idempotencia la resuelve el inbox y el trabajo lo hace
 * {@code MasterDataEventHandler}, los dos en la capa de aplicación y los dos probables sin
 * broker.</p>
 *
 * <h2>Qué pasa cuando algo falla</h2>
 *
 * <p>Hay dos tipos de fallo y no se tratan igual, porque reintentar lo que nunca va a funcionar
 * solo retrasa el diagnóstico y ocupa el consumidor:</p>
 *
 * <ul>
 *   <li><b>Permanente</b> —un sobre sin {@code data}, un mensaje sin identificador estable, un JSON
 *       que no encaja en el contrato—: se lanza {@link AmqpRejectAndDontRequeueException}, que salta
 *       el interceptor de reintentos y manda el mensaje a la DLQ en la primera entrega.</li>
 *   <li><b>Transitorio</b> —lo que lance el manejador—: se propaga tal cual para que el contenedor
 *       lo reintente con el backoff configurado y, agotados los intentos, lo mande también a la
 *       DLQ. El inbox ya ha dejado la fila en {@code FAILED} con el motivo.</li>
 * </ul>
 *
 * <p>En ningún caso se traga la excepción. Un {@code catch} que solo registrase el error haría que
 * el contenedor confirmara el mensaje como procesado: el evento se perdería y la cola de errores
 * quedaría vacía, que es justo lo que hace creer que no ha pasado nada.</p>
 *
 * <p>Una entrega repetida de un mensaje ya aplicado <b>no</b> es un fallo: el procesador devuelve
 * {@code DUPLICATE_SKIPPED}, el listener vuelve sin excepción y el contenedor confirma. Es lo que
 * evita que un duplicado dé vueltas por la cola o acabe en la DLQ.</p>
 */
@RequiredArgsConstructor
public class MasterDataEventConsumer {

    private static final Logger LOGGER = LoggerFactory.getLogger(MasterDataEventConsumer.class);

    private final MasterDataEventProcessor masterDataEventProcessor;

    /**
     * La cola se resuelve por placeholder porque una anotación solo admite constantes, y el valor
     * por defecto es la misma constante del contrato que usa {@code MasterDataRabbitProperties}:
     * así el listener y la declaración de la cola no pueden acabar apuntando a sitios distintos si
     * alguien borra la línea del YAML.
     */
    @RabbitListener(
            queues = "${app.rabbitmq.master-data.queue:" + MasterDataRabbitMqNames.STOCK_MASTER_DATA_QUEUE + "}",
            containerFactory = RabbitListenerContainerFactoryNames.MASTER_DATA)
    public void onMasterDataChanged(MasterDataChangedMessage message, Message rawMessage) {
        MessageProperties properties = rawMessage.getMessageProperties();

        LOGGER.info("Master data message received: exchange={}, routingKey={}, queue={}, messageId={}, "
                        + "eventType={}, aggregateType={}, aggregateId={}, sequenceNumber={}, signatureAlgorithm={}",
                properties.getReceivedExchange(),
                properties.getReceivedRoutingKey(),
                properties.getConsumerQueue(),
                properties.getMessageId(),
                properties.getHeader(MasterDataMessageHeaders.EVENT_TYPE),
                properties.getHeader(MasterDataMessageHeaders.AGGREGATE_TYPE),
                properties.getHeader(MasterDataMessageHeaders.AGGREGATE_ID),
                properties.getHeader(MasterDataMessageHeaders.SEQUENCE_NUMBER),
                properties.getHeader(MasterDataMessageHeaders.SIGNATURE_ALGORITHM));

        rejectIfUnprocessable(message, properties);

        InboxMessageCommand command = InboxMessageCommandFactory.from(message, rawMessage);
        InboxProcessingResult result = masterDataEventProcessor.process(command, message);

        LOGGER.info("Master data message settled: messageId={}, idempotencyKey={}, result={}",
                properties.getMessageId(), command.messageId(), result);
    }

    /**
     * Un sobre sin payload no es un fallo del que se pueda salir reintentando: el mensaje que hay
     * en la cola no cambia por volver a leerlo.
     */
    private static void rejectIfUnprocessable(MasterDataChangedMessage message, MessageProperties properties) {
        if (message == null || message.data() == null) {
            LOGGER.error("Master data message without payload sent to the dead letter queue: "
                            + "messageId={}, exchange={}, routingKey={}",
                    properties.getMessageId(),
                    properties.getReceivedExchange(),
                    properties.getReceivedRoutingKey());

            throw new AmqpRejectAndDontRequeueException(
                    "Master data message has no 'data' payload: messageId=" + properties.getMessageId());
        }
    }
}
