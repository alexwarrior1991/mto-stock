package com.alejandro.mtostock.application.dto.common;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a component of a request that an {@code Idempotency-Key} does not compare: a retry may carry
 * another value, and the first request's value stands.
 *
 * <p>Es para lo que dice cuándo, no qué: la fecha de una reserva o de una salida. Un cliente que
 * reintenta con la hora de cada intento manda la misma petición, y compararla la rechazaba con 409
 * aunque fuera la misma. Lo que dice qué se reserva o qué sale (material, almacén, proyecto,
 * cantidad...) sigue contando entero.</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface IdempotencyIgnored {
}
