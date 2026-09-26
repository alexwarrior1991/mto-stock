package com.alejandro.mtostock.application.exception;

/**
 * Raised when an {@code Idempotency-Key} comes back with a different request body.
 *
 * <p>No es una petición nueva ni un reintento: un reintento manda el mismo cuerpo. Aplicarla
 * escribiría algo que el cliente cree que ya existe, y devolver lo de la primera vez le diría que se
 * hizo lo que no pidió, así que se rechaza sin escribir nada.</p>
 */
public class IdempotencyKeyConflictException extends BusinessException {

    public IdempotencyKeyConflictException(String idempotencyKey) {
        super("Idempotency key '%s' was already used with a different request".formatted(idempotencyKey));
    }
}
