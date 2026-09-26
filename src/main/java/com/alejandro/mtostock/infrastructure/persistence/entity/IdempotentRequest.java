package com.alejandro.mtostock.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
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

import java.util.UUID;

/**
 * A write made with an {@code Idempotency-Key}: which operation, whose key, the fingerprint of the
 * body and what it created.
 *
 * <p>Como {@link InboxMessage}, se escribe solo con las sentencias nativas de
 * {@code IdempotentRequestRepository}: reclamar la clave tiene que ser un {@code insert ... on
 * conflict} con recuento de filas, porque un leer-y-guardar deja pasar a dos peticiones simultáneas
 * con la misma clave. La garantía la da la restricción única {@code (operation, created_by,
 * idempotency_key)}, no esta clase. El mapeo existe para leer la fila de la petición anterior y para
 * que {@code ddl-auto: validate} compruebe que el esquema y el código no se han separado.</p>
 *
 * <p>{@code created_by} es quien mandó la petición y forma parte de la clave: la de un cliente no
 * choca con la de otro. No lleva {@code @Audited}: Envers no ve las escrituras nativas (ver
 * {@link AuditableEntity}).</p>
 */
@Entity
@Table(
        name = "idempotent_request",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_idempotent_request_key",
                columnNames = {"operation", "created_by", "idempotency_key"}
        )
)
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@ToString(callSuper = true, onlyExplicitlyIncluded = true)
public class IdempotentRequest extends AuditableEntity {

    @NotNull
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "operation", nullable = false, updatable = false, columnDefinition = "idempotent_operation")
    @ToString.Include
    private IdempotentOperation operation;

    @NotBlank
    @Size(max = 255)
    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 255)
    @ToString.Include
    private String idempotencyKey;

    /** SHA-256 del cuerpo: la misma clave con otro cuerpo es un error del cliente. */
    @NotBlank
    @Size(max = 64)
    @Column(name = "request_hash", nullable = false, updatable = false, length = 64)
    private String requestHash;

    /**
     * La reserva o el movimiento creados. Solo está vacío dentro de la transacción que reclama la
     * clave, antes de escribir; ninguna otra lo llega a ver así.
     */
    @Column(name = "resource_id")
    @ToString.Include
    private UUID resourceId;
}
