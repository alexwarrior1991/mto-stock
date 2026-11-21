package com.alejandro.mtostock.infrastructure.persistence.repository;

import com.alejandro.mtostock.infrastructure.persistence.entity.InboxMessage;
import com.alejandro.mtostock.infrastructure.persistence.entity.InboxMessageStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for the consumer inbox and its atomic idempotency operations.
 *
 * <p>Las sentencias de escritura son nativas y condicionales a propósito. La alternativa —cargar la
 * entidad, mirar su estado y guardar— tiene una carrera entre la lectura y la escritura por la que
 * dos entregas simultáneas del mismo mensaje ejecutan las dos el manejador. Aquí decide la base de
 * datos, y lo hace en dos puntos distintos según cuándo lleguen las entregas:</p>
 *
 * <ul>
 *   <li><b>Primera entrega y su duplicado, a la vez.</b> La segunda no ve la fila que la primera
 *       acaba de insertar sin confirmar, así que lo que la detiene no es ningún bloqueo de fila
 *       sino el índice único: su {@code insert ... on conflict} espera en él hasta que la primera
 *       transacción termina. Si confirmó, la reclamación posterior encuentra la fila ya aplicada y
 *       devuelve 0; si revirtió, inserta ella y se queda el trabajo, de modo que un fallo de la
 *       primera no pierde el evento.</li>
 *   <li><b>Entregas posteriores, con la fila ya confirmada.</b> La inserción no hace nada y quien
 *       serializa es el bloqueo de fila de {@link #claimForProcessing}: la segunda espera ahí y,
 *       al soltarse el bloqueo, PostgreSQL vuelve a evaluar el {@code where} sobre la versión
 *       nueva, ve el {@code PROCESSED} y devuelve 0.</li>
 * </ul>
 *
 * <p>Por los dos caminos el manejador se ejecuta exactamente una vez.</p>
 */
public interface InboxMessageRepository extends JpaRepository<InboxMessage, UUID> {

    Optional<InboxMessage> findByMessageIdAndSourceService(String messageId, String sourceService);

    List<InboxMessage> findByStatus(InboxMessageStatus status);

    /**
     * Registra el mensaje si es la primera vez que se ve, y no hace nada si ya está.
     *
     * <p>Devuelve 0 cuando la fila ya existía; ese 0 no significa duplicado ya procesado, solo que
     * no había nada que insertar. Quien decide si hay que ejecutar el manejador es
     * {@link #claimForProcessing}.</p>
     */
    @Modifying
    @Query(value = """
            insert into inbox_message (
                id,
                message_id,
                source_service,
                event_type,
                aggregate_type,
                aggregate_id,
                exchange_name,
                routing_key,
                queue_name,
                payload_hash,
                payload,
                status,
                received_at,
                processing_attempts,
                created_at,
                updated_at,
                created_by,
                updated_by
            ) values (
                gen_random_uuid(),
                :messageId,
                :sourceService,
                :eventType,
                :aggregateType,
                :aggregateId,
                :exchangeName,
                :routingKey,
                :queueName,
                :payloadHash,
                cast(:payload as json),
                'RECEIVED',
                now(),
                0,
                now(),
                now(),
                'system',
                'system'
            ) on conflict (message_id, source_service) do nothing
            """, nativeQuery = true)
    int insertIfMissing(
            @Param("messageId") String messageId,
            @Param("sourceService") String sourceService,
            @Param("eventType") String eventType,
            @Param("aggregateType") String aggregateType,
            @Param("aggregateId") String aggregateId,
            @Param("exchangeName") String exchangeName,
            @Param("routingKey") String routingKey,
            @Param("queueName") String queueName,
            @Param("payloadHash") String payloadHash,
            @Param("payload") String payload
    );

    /**
     * Reclama el mensaje para ejecutarlo, salvo que ya esté aplicado.
     *
     * <p>Devuelve 1 si esta entrega se queda el trabajo y 0 si el mensaje ya estaba
     * {@code PROCESSED}, que es la señal de duplicado.</p>
     *
     * <p>Un {@code PROCESSING} sí se reclama. Ese estado no llega a confirmarse en el camino normal
     * —se escribe y se sustituye por {@code PROCESSED} dentro de la misma transacción—, así que
     * encontrárselo significa que el proceso que lo escribió murió a mitad. Volver a ejecutarlo es
     * lo único que hace que el evento acabe aplicándose; dejarlo pasar lo perdería para siempre sin
     * que nadie se enterase.</p>
     *
     * <p>El motivo del fallo anterior se limpia al reclamar: mientras el intento está en marcha, la
     * fila ya no está fallida, y dejar el motivo puesto haría que un mensaje aplicado con éxito
     * apareciese con un error al lado.</p>
     */
    @Modifying
    @Query(value = """
            update inbox_message
               set status = 'PROCESSING',
                   processing_attempts = processing_attempts + 1,
                   failed_at = null,
                   failure_reason = null,
                   updated_at = now()
             where message_id = :messageId
               and source_service = :sourceService
               and status <> 'PROCESSED'
            """, nativeQuery = true)
    int claimForProcessing(
            @Param("messageId") String messageId,
            @Param("sourceService") String sourceService
    );

    @Modifying
    @Query(value = """
            update inbox_message
               set status = 'PROCESSED',
                   processed_at = now(),
                   updated_at = now()
             where message_id = :messageId
               and source_service = :sourceService
               and status = 'PROCESSING'
            """, nativeQuery = true)
    int markProcessed(
            @Param("messageId") String messageId,
            @Param("sourceService") String sourceService
    );

    /**
     * Deja constancia del fallo, exista ya la fila o no.
     *
     * <p>Es una inserción con {@code on conflict do update} y no un {@code update} porque se ejecuta
     * en una transacción aparte, después de que la del intento haya revertido: en la primera entrega
     * de un mensaje, esa reversión se lleva por delante la fila recién insertada y no queda nada que
     * actualizar.</p>
     *
     * <p>El contador de intentos se incrementa aquí, y no en {@link #claimForProcessing}, para el
     * camino de fallo: el incremento de la reclamación revierte junto con el resto del intento.</p>
     */
    @Modifying
    @Query(value = """
            insert into inbox_message (
                id,
                message_id,
                source_service,
                event_type,
                aggregate_type,
                aggregate_id,
                exchange_name,
                routing_key,
                queue_name,
                payload_hash,
                payload,
                status,
                received_at,
                failed_at,
                failure_reason,
                processing_attempts,
                created_at,
                updated_at,
                created_by,
                updated_by
            ) values (
                gen_random_uuid(),
                :messageId,
                :sourceService,
                :eventType,
                :aggregateType,
                :aggregateId,
                :exchangeName,
                :routingKey,
                :queueName,
                :payloadHash,
                cast(:payload as json),
                'FAILED',
                now(),
                now(),
                :failureReason,
                1,
                now(),
                now(),
                'system',
                'system'
            ) on conflict (message_id, source_service) do update
               set status = 'FAILED',
                   failed_at = now(),
                   failure_reason = excluded.failure_reason,
                   processing_attempts = inbox_message.processing_attempts + 1,
                   updated_at = now()
            """, nativeQuery = true)
    int recordFailure(
            @Param("messageId") String messageId,
            @Param("sourceService") String sourceService,
            @Param("eventType") String eventType,
            @Param("aggregateType") String aggregateType,
            @Param("aggregateId") String aggregateId,
            @Param("exchangeName") String exchangeName,
            @Param("routingKey") String routingKey,
            @Param("queueName") String queueName,
            @Param("payloadHash") String payloadHash,
            @Param("payload") String payload,
            @Param("failureReason") String failureReason
    );

    /**
     * Borra un lote de mensajes aplicados hace mas de lo que se recuerdan.
     *
     * <p>El corte se pide dos veces: {@code received_at} es lo que recorre el indice
     * {@code idx_inbox_message_status_received_at} (un mensaje no se aplica antes de llegar, asi que
     * ninguno que cumpla el plazo queda fuera), y {@code processed_at} es el plazo de verdad, para no
     * olvidar al dia siguiente el que llego hace meses, fallo y se aplico ayer al reencolarlo. No hace
     * falta otro indice.</p>
     *
     * <p>{@code skip locked}: la fila que una entrega esta reclamando en ese momento se deja para la
     * pasada siguiente, y dos replicas purgando a la vez no se esperan.</p>
     */
    @Modifying
    @Transactional
    @Query(value = """
            delete from inbox_message
             where id in (select id from inbox_message
                           where status = 'PROCESSED' and received_at < :before and processed_at < :before
                           order by received_at
                           limit :batchSize
                           for update skip locked)
            """, nativeQuery = true)
    int purgeProcessedBefore(@Param("before") Instant before, @Param("batchSize") int batchSize);

    /**
     * Borra un lote de mensajes fallidos cuyo ultimo fallo es anterior al corte. Una fallida no protege
     * de nada (al reencolarla se reclama otra vez, y si ya no esta se inserta de nuevo): se guarda mas
     * tiempo solo porque dice por que el mensaje acabo en la DLQ.
     */
    @Modifying
    @Transactional
    @Query(value = """
            delete from inbox_message
             where id in (select id from inbox_message
                           where status = 'FAILED' and received_at < :before and failed_at < :before
                           order by received_at
                           limit :batchSize
                           for update skip locked)
            """, nativeQuery = true)
    int purgeFailedBefore(@Param("before") Instant before, @Param("batchSize") int batchSize);
}
