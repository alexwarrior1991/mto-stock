package com.alejandro.mtostock.application.service;

import com.alejandro.mtostock.application.dto.messaging.InboxMessageCommand;
import com.alejandro.mtostock.application.dto.messaging.InboxProcessingResult;

/**
 * Runs a piece of work at most once per message, whatever the broker delivers.
 *
 * <p>Es una abstracción de aplicación: no conoce RabbitMQ, solo el {@link InboxMessageCommand} que
 * le pasa quien sí lo conoce. La idempotencia la garantiza la restricción única de
 * {@code inbox_message}, no esta interfaz.</p>
 */
public interface InboxMessageService {

    /**
     * Registra el mensaje y ejecuta {@code processing} solo si no se había aplicado ya.
     *
     * <p>Todo ocurre en una transacción: el registro, la reclamación, el trabajo y la marca de
     * aplicado se confirman juntos o no se confirma nada. Si {@code processing} lanza, la
     * transacción revierte entera y no queda un mensaje marcado como aplicado cuyo trabajo se
     * deshizo.</p>
     *
     * @param processing el trabajo a ejecutar una sola vez; lo que lance se propaga al llamante
     * @return {@code PROCESSED} si se ejecutó, {@code DUPLICATE_SKIPPED} si ya estaba aplicado
     */
    InboxProcessingResult process(InboxMessageCommand command, Runnable processing);

    /**
     * Deja el mensaje registrado como fallido, en su propia transacción.
     *
     * <p>Va aparte porque {@link #process} revierte al fallar, y un estado escrito dentro de esa
     * transacción se iría con ella: el mensaje acabaría en la DLQ sin que la tabla recordase por
     * qué. Tiene que llamarse <b>después</b> de que {@code process} haya terminado, nunca desde
     * dentro: una transacción nueva que intentase tocar la fila que la otra tiene bloqueada se
     * quedaría esperando a una transacción que a su vez espera a que ésta devuelva.</p>
     */
    void recordFailure(InboxMessageCommand command, Throwable failure);
}
