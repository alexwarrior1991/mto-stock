package com.alejandro.mtostock.infrastructure.messaging.rabbitmq;

import com.alejandro.mtostock.application.dto.messaging.MasterDataChangedMessage;
import com.alejandro.mtostock.application.service.MasterDataEventHandler;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

/**
 * Reads master data messages off the queue and hands them to the application layer.
 *
 * <p>El listener es deliberadamente delgado: registra de dónde viene el mensaje, comprueba que el
 * sobre trae payload y delega. No hay lógica de negocio aquí y no debe haberla — lo que se decida
 * hacer con un cambio de datos maestros va en {@link MasterDataEventHandler}, que vive en la capa
 * de aplicación y se prueba sin broker.</p>
 *
 * <h2>Qué pasa cuando algo falla</h2>
 *
 * <p>Hay dos tipos de fallo y no se tratan igual, porque reintentar lo que nunca va a funcionar
 * solo retrasa el diagnóstico y ocupa el consumidor:</p>
 *
 * <ul>
 *   <li><b>Permanente</b> —un sobre sin {@code data}, un JSON que no encaja en el contrato—: se
 *       lanza {@link AmqpRejectAndDontRequeueException}, que salta el interceptor de reintentos y
 *       manda el mensaje a la DLQ en la primera entrega.</li>
 *   <li><b>Transitorio</b> —lo que lance el manejador—: se propaga tal cual para que el contenedor
 *       lo reintente con el backoff configurado y, agotados los intentos, lo mande también a la
 *       DLQ.</li>
 * </ul>
 *
 * <p>En ningún caso se traga la excepción. Un {@code catch} que solo registrase el error haría que
 * el contenedor confirmara el mensaje como procesado: el evento se perdería y la cola de errores
 * quedaría vacía, que es justo lo que hace creer que no ha pasado nada.</p>
 */
@RequiredArgsConstructor
public class MasterDataEventConsumer {

    private static final Logger LOGGER = LoggerFactory.getLogger(MasterDataEventConsumer.class);

    private final MasterDataEventHandler masterDataEventHandler;

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

        try {
            masterDataEventHandler.handle(message);
        } catch (AmqpRejectAndDontRequeueException exception) {
            LOGGER.error("Master data message rejected without retry: messageId={}, eventType={}, operationId={}",
                    properties.getMessageId(), message.eventType(), message.operationId(), exception);
            throw exception;
        } catch (RuntimeException exception) {
            LOGGER.error("Master data message failed and will be retried: messageId={}, eventType={}, "
                            + "operationId={}, referenceId={}",
                    properties.getMessageId(), message.eventType(), message.operationId(),
                    message.referenceId(), exception);
            throw exception;
        }
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
