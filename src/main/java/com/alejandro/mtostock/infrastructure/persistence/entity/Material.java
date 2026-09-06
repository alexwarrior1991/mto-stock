package com.alejandro.mtostock.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.envers.Audited;

import java.math.BigDecimal;

/**
 * Persistent catalogue material stored and moved through warehouses.
 */
@Audited
@Entity
@Table(
        name = "material",
        uniqueConstraints = @UniqueConstraint(name = "uq_material_code", columnNames = "code"),
        indexes = @Index(name = "idx_material_active", columnList = "active")
)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
@ToString(callSuper = true, onlyExplicitlyIncluded = true)
public class Material extends AuditableEntity {

    @NotBlank
    @Size(max = 64)
    @Column(name = "code", nullable = false, length = 64, unique = true)
    @ToString.Include
    private String code;

    @NotBlank
    @Size(max = 255)
    @Column(name = "name", nullable = false)
    @ToString.Include
    private String name;

    @NotBlank
    @Size(max = 32)
    @Column(name = "unit_of_measure", nullable = false, length = 32)
    private String unitOfMeasure;

    @NotNull
    @PositiveOrZero
    @Digits(integer = 13, fraction = 6)
    @Builder.Default
    @Column(name = "minimum_stock_level", nullable = false, precision = 19, scale = 6)
    private BigDecimal minimumStockLevel = BigDecimal.ZERO;

    @NotNull
    @Builder.Default
    @Column(name = "active", nullable = false)
    private Boolean active = true;

}