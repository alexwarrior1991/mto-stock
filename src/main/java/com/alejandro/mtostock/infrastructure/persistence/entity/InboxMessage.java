package com.alejandro.mtostock.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Persistent record of every message consumed from {@code mto-configuration}.
 *
 * <p>Es la mitad consumidora del par Outbox/Inbox: el emisor garantiza que el evento se publica al
 * menos una vez, y esta tabla garantiza que se aplica exactamente una. La garantía la da la
 * restricción única {@code (message_id, source_service)} de la base de datos, no esta clase.</p>
 *
 * <p>La entidad se escribe casi siempre con las sentencias nativas de
 * {@code InboxMessageRepository}, no con {@code save}: registrar, reclamar y marcar tienen que ser
 * operaciones atómicas con recuento de filas, y un leer-modificar-guardar deja pasar duplicados
 * entre la lectura y la escritura. El mapeo existe para poder leer las filas —tests, diagnóstico,
 * un futuro reproceso— y para que {@code ddl-auto: validate} compruebe que el esquema y el código
 * no se han separado.</p>
 */
@Entity
@Table(
        name = "inbox_message",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_inbox_message_message_id_source",
                columnNames = {"message_id", "source_service"}
        ),
        indexes = {
                @Index(name = "idx_inbox_message_status_received_at", columnList = "status, received_at"),
                @Index(name = "idx_inbox_message_received_at", columnList = "received_at")
        }
)
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@ToString(callSuper = true, onlyExplicitlyIncluded = true)
public class InboxMessage extends AuditableEntity {

    @NotBlank
    @Size(max = 200)
    @Column(name = "message_id", nullable = false, updatable = false, length = 200)
    @ToString.Include
    private String messageId;

    @NotBlank
    @Size(max = 100)
    @Column(name = "source_service", nullable = false, updatable = false, length = 100)
    @ToString.Include
    private String sourceService;

    @Size(max = 150)
    @Column(name = "event_type", length = 150)
    @ToString.Include
    private String eventType;

    @Size(max = 150)
    @Column(name = "aggregate_type", length = 150)
    private String aggregateType;

    @Size(max = 100)
    @Column(name = "aggregate_id", length = 100)
    private String aggregateId;

    @Size(max = 255)
    @Column(name = "exchange_name", length = 255)
    private String exchangeName;

    @Size(max = 255)
    @Column(name = "routing_key", length = 255)
    private String routingKey;

    @Size(max = 255)
    @Column(name = "queue_name", length = 255)
    private String queueName;

    /** Dato auxiliar para correlacionar; nunca la clave de idempotencia. */
    @Size(max = 64)
    @Column(name = "payload_hash", length = 64)
    private String payloadHash;

    /**
     * El JSON tal y como llegó, carácter a carácter.
     *
     * <p>La columna es {@code json} y no {@code jsonb} porque jsonb normaliza al guardar —reordena
     * las claves y colapsa los espacios—, y entonces lo que se lee ya no es lo que se recibió ni
     * casa con {@link #payloadHash}.</p>
     */
    @NotBlank
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "json")
    private String payload;

    @NotNull
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", nullable = false, columnDefinition = "inbox_message_status")
    @ToString.Include
    private InboxMessageStatus status = InboxMessageStatus.RECEIVED;

    @NotNull
    @Builder.Default
    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt = Instant.now();

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "failed_at")
    private Instant failedAt;

    @Column(name = "failure_reason", columnDefinition = "text")
    private String failureReason;

    @Min(0)
    @Builder.Default
    @Column(name = "processing_attempts", nullable = false)
    @ToString.Include
    private int processingAttempts = 0;

    public boolean isProcessed() {
        return InboxMessageStatus.PROCESSED == status;
    }
}
