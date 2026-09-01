package com.alejandro.mtostock.application.dto.messaging;

import java.time.Instant;
import java.util.UUID;

/**
 * Envelope of every asynchronous message published by {@code mto-configuration}.
 *
 * <p>Réplica del record {@code AsynchronousMessage<T>} del emisor, fijado aquí a
 * {@link MasterDataChangedEvent} en lugar de genérico: este servicio consume un único tipo de
 * evento y un sobre concreto se deserializa sin depender de la inferencia de genéricos del
 * convertidor.</p>
 *
 * <p>Sobre {@code messageHash}: <b>no</b> es una firma y no sirve para verificar integridad. El
 * emisor lo calcula sobre el objeto ANTES de serializarlo, de modo que recomprobarlo obligaría a
 * deserializar y volver a serializar, y esa ida y vuelta no conserva la identidad (un
 * {@code BigDecimal} 1.50 vuelve como 1.5 y da otro hash). La comprobación que sí es verificable
 * viaja en la cabecera {@code messageSignature}, firmada sobre los bytes reales; ver
 * {@code com.alejandro.mtostock.infrastructure.messaging.rabbitmq.MasterDataMessageHeaders}.</p>
 *
 * @param operationId  identificador de la operación de negocio que originó el evento; es lo que
 *                     correlaciona la traza de punta a punta
 * @param referenceId  referencia legible del agregado, {@code <entidad>-<id>}
 * @param origin       servicio emisor, normalmente {@code mto-configuration}
 * @param creationDate instante en que el emisor creó el mensaje
 * @param eventType    tipo de evento, {@code MASTER_DATA_<ENTIDAD>_<OPERACIÓN>}
 * @param data         payload de negocio
 * @param messageHash  huella del contenido en origen, útil para correlacionar y detectar
 *                     duplicados; no es garantía de integridad
 */
public record MasterDataChangedMessage(
        UUID operationId,
        String referenceId,
        String origin,
        Instant creationDate,
        String eventType,
        MasterDataChangedEvent data,
        String messageHash
) {
}
