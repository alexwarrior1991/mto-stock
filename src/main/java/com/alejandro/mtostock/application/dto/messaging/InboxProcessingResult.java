package com.alejandro.mtostock.application.dto.messaging;

/**
 * Outcome of handing one message to the inbox.
 *
 * <p>No hay valor para «fallido»: un fallo sale como excepción, no como resultado. Devolverlo como
 * un valor más obligaría a cada llamante a acordarse de mirarlo, y quien se olvide confirmaría al
 * broker un mensaje que no se ha aplicado — justo la pérdida silenciosa que el inbox existe para
 * evitar.</p>
 */
public enum InboxProcessingResult {

    /** Primera aplicación efectiva: el manejador se ejecutó y el mensaje quedó registrado. */
    PROCESSED,

    /** Entrega repetida de un mensaje ya aplicado: no se ejecutó nada y se confirma igualmente. */
    DUPLICATE_SKIPPED
}
