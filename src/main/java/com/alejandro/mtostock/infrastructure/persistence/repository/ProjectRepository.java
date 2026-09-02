package com.alejandro.mtostock.infrastructure.persistence.repository;

import com.alejandro.mtostock.infrastructure.persistence.entity.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Thin Spring Data repository for project persistence.
 */
public interface ProjectRepository extends JpaRepository<Project, UUID> {

    Optional<Project> findByCode(String code);
    
    boolean existsByCode(String code);

    Optional<Project> findBySourceServiceAndSourceEntityId(String sourceService, String sourceEntityId);

    /**
     * Da de alta o actualiza el proyecto que corresponde a una entidad de otro servicio.
     *
     * <p>Una sola sentencia y no un «buscar, y si no está insertar»: entre esas dos operaciones caben
     * dos entregas del mismo paquete de ejecución, y la segunda insertaría una fila duplicada o
     * fallaría por la restricción única. Es el mismo motivo por el que el inbox y las existencias
     * escriben así.</p>
     *
     * <p>{@code code} no se actualiza al haber conflicto: se deriva del identificador de origen, que
     * es justo la clave del conflicto, así que no puede haber cambiado. Reescribirlo solo serviría
     * para chocar con un código que alguien haya puesto a mano.</p>
     */
    @Modifying
    @Query(value = """
            insert into project (
                id,
                code,
                name,
                active,
                source_service,
                source_entity_id,
                created_at,
                updated_at,
                created_by,
                updated_by
            ) values (
                gen_random_uuid(),
                :code,
                :name,
                :active,
                :sourceService,
                :sourceEntityId,
                now(),
                now(),
                'system',
                'system'
            ) on conflict (source_service, source_entity_id) do update
               set name = excluded.name,
                   active = excluded.active,
                   updated_at = now()
            """, nativeQuery = true)
    int upsertFromMasterData(
            @Param("sourceService") String sourceService,
            @Param("sourceEntityId") String sourceEntityId,
            @Param("code") String code,
            @Param("name") String name,
            @Param("active") boolean active
    );

    /**
     * Desactiva el proyecto sincronizado desde otro servicio, sin borrarlo.
     *
     * <p>No se borra a propósito: {@code reservation} y {@code stock_movement} apuntan a
     * {@code project} con {@code on delete restrict}, así que un proyecto con historial no se puede
     * borrar aunque se quiera, y uno sin historial que desapareciera se llevaría por delante la
     * trazabilidad de por qué existió. Desactivar deja el dato y lo saca de circulación.</p>
     *
     * @return 0 si no había ningún proyecto de ese origen, que no es un error: puede llegar la baja
     *         de un paquete de ejecución que este servicio nunca llegó a ver
     */
    @Modifying
    @Query(value = """
            update project
               set active = false,
                   updated_at = now()
             where source_service = :sourceService
               and source_entity_id = :sourceEntityId
               and active = true
            """, nativeQuery = true)
    int deactivateFromMasterData(
            @Param("sourceService") String sourceService,
            @Param("sourceEntityId") String sourceEntityId
    );
}
