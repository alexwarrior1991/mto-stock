package com.alejandro.mtostock.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Persistent reservation that temporarily subtracts material from available stock for a project.
 */
@Entity
@Table(
        name = "reservation",
        indexes = {
                @Index(name = "idx_reservation_active_material_warehouse", columnList = "material_id, warehouse_id"),
                @Index(name = "idx_reservation_project_status", columnList = "project_id, status"),
                @Index(name = "idx_reservation_warehouse_status", columnList = "warehouse_id, status")
        }
)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
@ToString(callSuper = true, onlyExplicitlyIncluded = true)
public class Reservation extends AuditableEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "material_id", nullable = false, foreignKey = @ForeignKey(name = "fk_reservation_material"))
    private Material material;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "warehouse_id", nullable = false, foreignKey = @ForeignKey(name = "fk_reservation_warehouse"))
    private Warehouse warehouse;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false, foreignKey = @ForeignKey(name = "fk_reservation_project"))
    private Project project;

    @NotNull
    @Positive
    @Digits(integer = 13, fraction = 6)
    @Column(name = "quantity", nullable = false, precision = 19, scale = 6)
    private BigDecimal quantity;

    @NotNull
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Builder.Default
    @Column(name = "status", nullable = false, columnDefinition = "reservation_status")
    @ToString.Include
    private ReservationStatus status = ReservationStatus.ACTIVE;

    @NotNull
    @Builder.Default
    @Column(name = "reserved_at", nullable = false)
    private Instant reservedAt = Instant.now();

    @Column(name = "released_at")
    private Instant releasedAt;

    public boolean isActive() {
        return ReservationStatus.ACTIVE == status;
    }

    public void release(Instant releaseTime) {
        changeTerminalStatus(ReservationStatus.RELEASED, releaseTime);
    }

    public void consume(Instant consumptionTime) {
        changeTerminalStatus(ReservationStatus.CONSUMED, consumptionTime);
    }

    public void cancel(Instant cancellationTime) {
        changeTerminalStatus(ReservationStatus.CANCELLED, cancellationTime);
    }

    private void changeTerminalStatus(ReservationStatus terminalStatus, Instant releaseTime) {
        if (!isActive()) {
            throw new IllegalStateException("only active reservations can be released or cancelled");
        }
        status = terminalStatus;
        releasedAt = Objects.requireNonNull(releaseTime, "release time is required");
    }

}