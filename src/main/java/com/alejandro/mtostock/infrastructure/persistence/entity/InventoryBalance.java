package com.alejandro.mtostock.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;

/**
 * Persistent current stock projection for fast material and warehouse balance reads.
 */
@Entity
@Table(
        name = "inventory_balance",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_inventory_balance_material_warehouse",
                columnNames = {"material_id", "warehouse_id"}
        ),
        indexes = {
                @Index(name = "idx_inventory_balance_material_id", columnList = "material_id"),
                @Index(name = "idx_inventory_balance_warehouse_id", columnList = "warehouse_id"),
                @Index(name = "idx_inventory_balance_material_warehouse", columnList = "material_id, warehouse_id")
        }
)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
@ToString(callSuper = true, onlyExplicitlyIncluded = true)
public class InventoryBalance extends AuditableEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "material_id", nullable = false, foreignKey = @ForeignKey(name = "fk_inventory_balance_material"))
    private Material material;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "warehouse_id", nullable = false, foreignKey = @ForeignKey(name = "fk_inventory_balance_warehouse"))
    private Warehouse warehouse;

    @NotNull
    @DecimalMin("0.000000")
    @Digits(integer = 13, fraction = 6)
    @Builder.Default
    @Column(name = "physical_quantity", nullable = false, precision = 19, scale = 6)
    private BigDecimal physicalQuantity = BigDecimal.ZERO;

    @NotNull
    @DecimalMin("0.000000")
    @Digits(integer = 13, fraction = 6)
    @Builder.Default
    @Column(name = "reserved_quantity", nullable = false, precision = 19, scale = 6)
    private BigDecimal reservedQuantity = BigDecimal.ZERO;

    @NotNull
    @DecimalMin("0.000000")
    @Digits(integer = 13, fraction = 6)
    @Builder.Default
    @Column(name = "available_quantity", nullable = false, precision = 19, scale = 6)
    private BigDecimal availableQuantity = BigDecimal.ZERO;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @AssertTrue(message = "available quantity must equal physical quantity minus reserved quantity")
    public boolean isAvailableQuantityConsistent() {
        if (physicalQuantity == null || reservedQuantity == null || availableQuantity == null) {
            return true;
        }
        return physicalQuantity.subtract(reservedQuantity).compareTo(availableQuantity) == 0;
    }
}