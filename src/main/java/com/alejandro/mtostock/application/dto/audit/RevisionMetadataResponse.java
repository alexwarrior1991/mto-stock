package com.alejandro.mtostock.application.dto.audit;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Quién hizo un cambio, cuándo y desde dónde.
 *
 * @param revision      número de revisión, creciente y compartido por todas las entidades que
 *                      cambiaron en la misma transacción
 * @param revisionAt    momento del cambio
 * @param operation     qué le pasó a la entidad
 * @param author        el mismo actor que va a {@code updated_by}: puede ser {@code system} (proceso
 *                      interno) o {@code unknown} (una escritura que perdió la identidad por el
 *                      camino), y la diferencia entre los dos es deliberada
 * @param source        canal del que vino la escritura; dice a qué espacio pertenece
 *                      {@code correlationId}
 * @param correlationId la cabecera {@code X-Correlation-Id} de la petición, o el identificador del
 *                      mensaje de RabbitMQ que la provocó
 */
@Schema(description = "Metadata of one change: who made it, when, and through which channel")
public record RevisionMetadataResponse(
        long revision,
        Instant revisionAt,
        RevisionOperation operation,
        String author,
        String source,
        String correlationId
) {
}
