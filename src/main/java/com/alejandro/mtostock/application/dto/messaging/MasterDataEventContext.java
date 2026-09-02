package com.alejandro.mtostock.application.dto.messaging;

/**
 * Transport metadata that travels beside the payload, not inside it.
 *
 * <p>Existe para que un manejador pueda ordenar sin conocer AMQP. El número de secuencia viaja en
 * una cabecera y no en el mensaje, así que no cabe en {@link MasterDataChangedMessage}: ese record
 * es copia fiel del contrato del emisor y meterle un campo que no publica lo haría mentir.</p>
 *
 * @param sequenceNumber número del mensaje en la secuencia del emisor, o {@code null} si no viajaba.
 *                       Es global y creciente, de modo que también lo es para los mensajes de un
 *                       mismo agregado: un número por debajo del último aplicado describe un estado
 *                       anterior al guardado
 */
public record MasterDataEventContext(Long sequenceNumber) {
}
