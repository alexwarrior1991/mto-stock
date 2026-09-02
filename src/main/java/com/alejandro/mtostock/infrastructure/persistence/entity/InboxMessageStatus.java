package com.alejandro.mtostock.infrastructure.persistence.entity;

/**
 * Lifecycle of a message recorded in the inbox.
 *
 * <p>Los nombres coinciden con los valores del tipo {@code inbox_message_status} de PostgreSQL:
 * renombrar una constante sin migrar el tipo rompe la lectura de las filas ya escritas.</p>
 */
public enum InboxMessageStatus {

    /**
     * Registrado y todavía sin reclamar. Es el valor por defecto de la columna; en el flujo normal
     * dura lo que tarda la misma transacción en pasar a {@link #PROCESSING}.
     */
    RECEIVED,

    /**
     * Reclamado por una entrega que está ejecutando el manejador.
     *
     * <p>En el camino normal no llega a confirmarse: se escribe y se sustituye por
     * {@link #PROCESSED} dentro de la misma transacción. Encontrarse una fila así quiere decir que
     * el proceso que la reclamó murió a mitad, y por eso se vuelve a reclamar.</p>
     */
    PROCESSING,

    /** Aplicado. Cualquier entrega posterior del mismo mensaje se descarta sin ejecutar nada. */
    PROCESSED,

    /**
     * El manejador falló. La fila conserva el motivo y el número de intentos, y una reentrega
     * vuelve a reclamarla.
     */
    FAILED
}
