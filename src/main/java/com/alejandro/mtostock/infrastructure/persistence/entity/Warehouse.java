package com.alejandro.mtostock.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
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
import org.hibernate.envers.Audited;

/**
 * Persistent warehouse or logical storage location affected by stock movements.
 */
@Audited
@Entity
@Table(
        name = "warehouse",
        uniqueConstraints = @UniqueConstraint(name = "uq_warehouse_code", columnNames = "code"),
        indexes = @Index(name = "idx_warehouse_active", columnList = "active")
)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
@ToString(callSuper = true, onlyExplicitlyIncluded = true)
public class Warehouse extends AuditableEntity {

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

    @NotNull
    @Builder.Default
    @Column(name = "active", nullable = false)
    private Boolean active = true;

}