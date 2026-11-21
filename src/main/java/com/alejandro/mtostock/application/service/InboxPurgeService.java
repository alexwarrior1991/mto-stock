package com.alejandro.mtostock.application.service;

/**
 * Olvida los mensajes del inbox que ya no pueden volver a llegar.
 *
 * <p>Sin ella {@code inbox_message} solo crece: una fila por evento consumido, con su JSON. Borra por
 * lotes, cada uno en su transaccion, los aplicados hace mas de {@code app.inbox.retention} y los
 * fallidos hace mas de {@code app.inbox.failed-retention}.</p>
 */
public interface InboxPurgeService {

    /** @return lo que borro esta pasada */
    Purged purge();

    /**
     * @param processed mensajes aplicados borrados
     * @param failed    mensajes fallidos borrados
     */
    record Purged(int processed, int failed) {
    }
}
