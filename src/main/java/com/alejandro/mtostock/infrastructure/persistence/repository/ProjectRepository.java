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
     *
     * <p>El {@code where} del {@code do update} es el que descarta los eventos que llegan tarde, y
     * está ahí y no en el código porque comprobar la marca de agua y escribirla tienen que ser la
     * misma operación: entre un {@code select} y un {@code update} caben dos entregas, y la que
     * llegue segunda vería una marca que ya no es la buena. Un evento sin número de secuencia se
     * aplica —no hay forma de saber que sea viejo— y conserva la marca anterior en lugar de
     * borrarla.</p>
     *
     * @return 1 si se dio de alta o se actualizó, y 0 si se descartó por venir por detrás de lo ya
     *         aplicado
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
                source_sequence_number,
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
                :sourceSequenceNumber,
                now(),
                now(),
                'system',
                'system'
            ) on conflict (source_service, source_entity_id) do update
               set name = excluded.name,
                   active = excluded.active,
                   source_sequence_number = coalesce(
                       excluded.source_sequence_number, project.source_sequence_number),
                   updated_at = now()
             where project.source_sequence_number is null
                or excluded.source_sequence_number is null
                or excluded.source_sequence_number >= project.source_sequence_number
            """, nativeQuery = true)
    int upsertFromMasterData(
            @Param("sourceService") String sourceService,
            @Param("sourceEntityId") String sourceEntityId,
            @Param("code") String code,
            @Param("name") String name,
            @Param("active") boolean active,
            @Param("sourceSequenceNumber") Long sourceSequenceNumber
    );

    /**
     * Desactiva el proyecto sincronizado desde otro servicio, sin borrarlo.
     *
     * <p>No se borra a propósito: {@code reservation} y {@code stock_movement} apuntan a
     * {@code project} con {@code on delete restrict}, así que un proyecto con historial no se puede
     * borrar aunque se quiera, y uno sin historial que desapareciera se llevaría por delante la
     * trazabilidad de por qué existió. Desactivar deja el dato y lo saca de circulación.</p>
     *
     * <p>No se exige que el proyecto esté activo: una baja repetida vuelve a escribir el mismo
     * {@code false} —que no cambia nada— pero sí adelanta la marca de agua, y ese es el punto. Si se
     * saltara la fila por estar ya inactiva, la marca se quedaría atrás y un {@code UPDATE}
     * retrasado por debajo del número de esta baja volvería a reactivar el proyecto.</p>
     *
     * @return 0 si no había ningún proyecto de ese origen o si el evento venía por detrás de lo ya
     *         aplicado; ninguna de las dos cosas es un error
     */
    @Modifying
    @Query(value = """
            update project
               set active = false,
                   source_sequence_number = coalesce(:sourceSequenceNumber, source_sequence_number),
                   updated_at = now()
             where source_service = :sourceService
               and source_entity_id = :sourceEntityId
               and (source_sequence_number is null
                    or cast(:sourceSequenceNumber as bigint) is null
                    or cast(:sourceSequenceNumber as bigint) >= source_sequence_number)
            """, nativeQuery = true)
    int deactivateFromMasterData(
            @Param("sourceService") String sourceService,
            @Param("sourceEntityId") String sourceEntityId,
            @Param("sourceSequenceNumber") Long sourceSequenceNumber
    );
}
