package com.alejandro.mtostock.infrastructure.persistence.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.Valid;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Persistent virtual assembly defined by a bill of materials instead of stored stock.
 */
@Entity
@Table(
        name = "assembly",
        uniqueConstraints = @UniqueConstraint(name = "uq_assembly_code", columnNames = "code"),
        indexes = @Index(name = "idx_assembly_active", columnList = "active")
)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
@ToString(callSuper = true, onlyExplicitlyIncluded = true)
public class Assembly extends AuditableEntity {

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

    @Valid
    @Builder.Default
    @OneToMany(mappedBy = "assembly", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<AssemblyComponent> components = new ArrayList<>();

    public void addComponent(AssemblyComponent component) {
        AssemblyComponent componentToAdd = Objects.requireNonNull(component, "component is required");
        componentToAdd.setAssembly(this);
        components.add(componentToAdd);
    }

    public void removeComponent(AssemblyComponent component) {
        if (components.remove(component)) {
            component.setAssembly(null);
        }
    }

}