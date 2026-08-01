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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Persistent append-only stock ledger row from which current stock is calculated.
 */
@Entity
@Table(
        name = "stock_movement",
        indexes = {
                @Index(name = "idx_stock_movement_material_warehouse_occurred_at", columnList = "material_id, warehouse_id, occurred_at"),
                @Index(name = "idx_stock_movement_warehouse_occurred_at", columnList = "warehouse_id, occurred_at"),
                @Index(name = "idx_stock_movement_type_occurred_at", columnList = "type, occurred_at"),
                @Index(name = "idx_stock_movement_supplier_id", columnList = "supplier_id"),
                @Index(name = "idx_stock_movement_project_id", columnList = "project_id"),
                @Index(name = "idx_stock_movement_reservation_id", columnList = "reservation_id"),
                @Index(name = "idx_stock_movement_related_movement_id", columnList = "related_movement_id")
        }
)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
@ToString(callSuper = true, onlyExplicitlyIncluded = true)
public class StockMovement extends AuditableEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "material_id", nullable = false, foreignKey = @ForeignKey(name = "fk_stock_movement_material"))
    private Material material;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "warehouse_id", nullable = false, foreignKey = @ForeignKey(name = "fk_stock_movement_warehouse"))
    private Warehouse warehouse;

    @NotNull
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "type", nullable = false, columnDefinition = "stock_movement_type")
    @ToString.Include
    private StockMovementType type;

    @NotNull
    @Positive
    @Digits(integer = 13, fraction = 6)
    @Column(name = "quantity", nullable = false, precision = 19, scale = 6)
    private BigDecimal quantity;

    @NotNull
    @Builder.Default
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt = Instant.now();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id", foreignKey = @ForeignKey(name = "fk_stock_movement_supplier"))
    private Supplier supplier;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", foreignKey = @ForeignKey(name = "fk_stock_movement_project"))
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reservation_id", foreignKey = @ForeignKey(name = "fk_stock_movement_reservation"))
    private Reservation reservation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "related_movement_id", foreignKey = @ForeignKey(name = "fk_stock_movement_related_movement"))
    private StockMovement relatedMovement;

    @Size(max = 128)
    @Column(name = "external_reference", length = 128)
    private String externalReference;

    @Column(name = "notes")
    private String notes;

    public BigDecimal signedQuantity() {
        return type.applyTo(quantity);
    }

    public void relateTo(StockMovement movement) {
        StockMovement movementToRelate = Objects.requireNonNull(movement, "related movement is required");
        if (movementToRelate == this) {
            throw new IllegalArgumentException("stock movement cannot be related to itself");
        }
        relatedMovement = movementToRelate;
    }

}