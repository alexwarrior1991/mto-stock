package com.alejandro.mtostock.infrastructure.persistence.entity;

/**
 * The writes a client can repeat with an {@code Idempotency-Key} without duplicating them.
 *
 * <p>Los nombres coinciden con los valores del tipo {@code idempotent_operation} de PostgreSQL:
 * renombrar una constante sin migrar el tipo rompe la lectura de las filas ya escritas. Cada
 * operación tiene su propio espacio de claves: la misma clave en una reserva y en una salida son dos
 * peticiones distintas.</p>
 */
public enum IdempotentOperation {

    /** {@code POST /reservations}: lo creado es la reserva. */
    RESERVATION,

    /** {@code POST /movements/outputs}: lo creado es el movimiento de salida. */
    OUTPUT
}
