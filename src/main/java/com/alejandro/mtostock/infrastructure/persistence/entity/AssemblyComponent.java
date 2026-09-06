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
import org.hibernate.envers.Audited;

import java.math.BigDecimal;

/**
 * Persistent BOM line linking an assembly to the material quantity required per unit.
 */
@Audited
@Entity
@Table(
        name = "assembly_component",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_assembly_component_assembly_material",
                columnNames = {"assembly_id", "material_id"}
        ),
        indexes = {
                @Index(name = "idx_assembly_component_assembly_id", columnList = "assembly_id"),
                @Index(name = "idx_assembly_component_material_id", columnList = "material_id")
        }
)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
@ToString(callSuper = true, onlyExplicitlyIncluded = true)
public class AssemblyComponent extends AuditableEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assembly_id", nullable = false, foreignKey = @ForeignKey(name = "fk_assembly_component_assembly"))
    private Assembly assembly;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "material_id", nullable = false, foreignKey = @ForeignKey(name = "fk_assembly_component_material"))
    private Material material;

    @NotNull
    @Positive
    @Digits(integer = 13, fraction = 6)
    @Column(name = "quantity", nullable = false, precision = 19, scale = 6)
    @ToString.Include
    private BigDecimal quantity;

}