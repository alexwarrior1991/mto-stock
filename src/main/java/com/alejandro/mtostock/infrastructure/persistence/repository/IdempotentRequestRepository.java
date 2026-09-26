package com.alejandro.mtostock.infrastructure.persistence.repository;

import com.alejandro.mtostock.infrastructure.persistence.entity.IdempotentOperation;
import com.alejandro.mtostock.infrastructure.persistence.entity.IdempotentRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for the writes made with an {@code Idempotency-Key}.
 *
 * <p>Las escrituras son nativas y condicionales, el mismo idioma que {@code InboxMessageRepository}:
 * con un leer-y-guardar, dos peticiones simultáneas con la misma clave verían las dos que no existe y
 * las dos escribirían. Aquí decide el índice único. La segunda {@link #claim} no ve la fila que la
 * primera acaba de insertar sin confirmar, pero espera en el índice hasta que la primera transacción
 * termina: si confirmó, no inserta nada y devuelve 0; si revirtió, inserta ella y la petición se
 * ejecuta como si fuera la primera.</p>
 */
public interface IdempotentRequestRepository extends JpaRepository<IdempotentRequest, UUID> {

    Optional<IdempotentRequest> findByOperationAndCreatedByAndIdempotencyKey(
            IdempotentOperation operation,
            String createdBy,
            String idempotencyKey
    );

    /**
     * Reclama la clave para esta petición.
     *
     * <p>Devuelve 1 si la clave era nueva y a esta petición le toca escribir, y 0 si ya la había
     * usado antes el mismo cliente para la misma operación: entonces la fila guardada dice con qué
     * cuerpo y qué se creó.</p>
     */
    @Modifying
    @Query(value = """
            insert into idempotent_request (
                id,
                operation,
                idempotency_key,
                request_hash,
                created_at,
                updated_at,
                created_by,
                updated_by
            ) values (
                gen_random_uuid(),
                cast(:operation as idempotent_operation),
                :idempotencyKey,
                :requestHash,
                now(),
                now(),
                :caller,
                :caller
            ) on conflict (operation, created_by, idempotency_key) do nothing
            """, nativeQuery = true)
    int claim(
            @Param("operation") String operation,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("requestHash") String requestHash,
            @Param("caller") String caller
    );

    /**
     * Apunta lo que creó la petición que reclamó la clave, en su misma transacción.
     *
     * <p>Devuelve 1, o 0 si no hay una reclamación pendiente con esa clave: una fila que ya tiene lo
     * suyo no se sobrescribe.</p>
     */
    @Modifying
    @Query(value = """
            update idempotent_request
               set resource_id = :resourceId,
                   updated_at = now()
             where operation = cast(:operation as idempotent_operation)
               and created_by = :caller
               and idempotency_key = :idempotencyKey
               and resource_id is null
            """, nativeQuery = true)
    int recordResource(
            @Param("operation") String operation,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("caller") String caller,
            @Param("resourceId") UUID resourceId
    );
}
