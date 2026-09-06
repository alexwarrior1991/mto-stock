package com.alejandro.mtostock.application.service;

import com.alejandro.mtostock.application.dto.audit.EntityRevisionResponse;
import com.alejandro.mtostock.application.dto.common.PageResponse;
import org.springframework.data.domain.Pageable;

import java.util.UUID;
import java.util.function.Function;

/**
 * Lee el historial de cambios que guarda Hibernate Envers.
 *
 * <p>Es uno solo y genérico, parametrizado por clase de entidad y función de conversión, en lugar de
 * un servicio por entidad: lo que cambia entre un material y una reserva es el Response que se
 * devuelve, no cómo se consulta el historial.</p>
 *
 * <p>Solo responde por las siete entidades auditadas. Ver {@code docs/07-auditing.md} para qué queda
 * fuera y por qué.</p>
 */
public interface EntityAuditService {

    /**
     * @param entityType  entidad JPA anotada con {@code @Audited}
     * @param id          identificador de la entidad
     * @param toResponse  convierte el estado de cada revisión al Response público del recurso
     * @param pageable    paginación; el historial se devuelve de la revisión más reciente a la más
     *                    antigua, que es el orden en el que se lee
     * @throws com.alejandro.mtostock.application.exception.NotFoundException si la entidad no tiene
     *         ninguna revisión, es decir, si nunca existió
     */
    <E, R> PageResponse<EntityRevisionResponse<R>> findRevisions(Class<E> entityType,
                                                                UUID id,
                                                                Function<E, R> toResponse,
                                                                Pageable pageable);
}
