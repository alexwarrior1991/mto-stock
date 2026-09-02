package com.alejandro.mtostock.application.service;

import com.alejandro.mtostock.application.dto.messaging.InboxMessageCommand;
import com.alejandro.mtostock.application.dto.messaging.InboxProcessingResult;
import com.alejandro.mtostock.application.dto.messaging.MasterDataChangedMessage;

/**
 * Applies one master data message exactly once.
 *
 * <p>Es lo único que el consumidor de RabbitMQ conoce: recibe el mensaje ya deserializado junto con
 * sus metadatos y devuelve qué se hizo con él. Que la idempotencia la garantice el inbox y que la
 * lógica la ejecute {@link MasterDataEventHandler} son detalles de la implementación, no del
 * listener.</p>
 */
public interface MasterDataEventProcessor {

    /**
     * @param command  metadatos y payload del mensaje, ya sin tipos de AMQP
     * @param message  el mensaje deserializado que recibirá el manejador
     * @return si el mensaje se aplicó o se descartó por ser un duplicado ya aplicado
     */
    InboxProcessingResult process(InboxMessageCommand command, MasterDataChangedMessage message);
}
