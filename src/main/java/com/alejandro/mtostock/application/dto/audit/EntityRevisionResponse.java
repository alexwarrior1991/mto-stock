package com.alejandro.mtostock.application.dto.audit;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Una entrada del historial: el estado que tenía la entidad en una revisión, y quién la dejó así.
 *
 * <p>{@code entity} es exactamente el mismo Response que devuelve el {@code GET} del recurso, para
 * que el cliente no tenga que aprender una segunda forma de leer un material o una reserva.</p>
 *
 * <p>En una revisión de borrado, {@code entity} trae el último estado conocido, no nulos: es lo que
 * hace {@code store_data_at_delete}, y es la diferencia entre «se borró algo» y «se borraron cuatro
 * unidades de este material». Aun así, los campos de auditoría del recurso
 * ({@code createdAt}/{@code updatedAt}/{@code createdBy}/{@code updatedBy}) vienen vacíos: no se
 * copian al historial porque el «quién y cuándo» de cada revisión ya está en {@code revision}.</p>
 */
@Schema(description = "One history entry: the entity as it was at a revision, plus that revision's metadata")
public record EntityRevisionResponse<T>(
        RevisionMetadataResponse revision,
        T entity
) {
}
