package com.alejandro.mtostock.infrastructure.persistence.audit;

/**
 * De qué canal vino la escritura que abrió una revisión.
 *
 * <p>Existe porque {@code correlation_id} guarda dos cosas distintas según el origen: la cabecera
 * {@code X-Correlation-Id} de una petición HTTP, o el identificador del mensaje de RabbitMQ. Son dos
 * espacios de identificadores que no se solapan, y sin esta columna no habría forma de saber contra
 * qué buscar uno dado.</p>
 */
public enum AuditRevisionSource {

    /** Petición HTTP: hay usuario, IP, User-Agent y URI. */
    HTTP,

    /** Consumo de un evento de datos maestros: {@code correlation_id} es el id del mensaje. */
    MESSAGING,

    /** Proceso interno sin petición ni mensaje: tarea programada, arranque, migración de datos. */
    SYSTEM,

    /**
     * La foto que dejó {@code V7} al instalar el historial. No dice que nadie creara nada en ese
     * momento: dice «así estaba esto cuando empezó a guardarse el historial».
     */
    BASELINE
}
