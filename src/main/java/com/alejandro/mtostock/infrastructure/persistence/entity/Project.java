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

/**
 * Persistent project that can own reservations and stock consumption movements.
 */
@Entity
@Table(
        name = "project",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_project_code", columnNames = "code"),
                @UniqueConstraint(name = "uq_project_source", columnNames = {"source_service", "source_entity_id"})
        },
        indexes = @Index(name = "idx_project_active", columnList = "active")
)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
@ToString(callSuper = true, onlyExplicitlyIncluded = true)
public class Project extends AuditableEntity {

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

    /**
     * Servicio del que se sincroniza este proyecto, o {@code null} si se creó a mano por la API.
     *
     * <p>Junto con {@link #sourceEntityId} forma la clave por la que se reconoce el mismo origen en
     * entregas sucesivas. No se usa {@link #code} para eso: los identificadores de
     * {@code mto-configuration} son numéricos, y {@code code} es un identificador de negocio que la
     * gente lee y escribe.</p>
     */
    @Size(max = 100)
    @Column(name = "source_service", length = 100)
    private String sourceService;

    /** Identificador de la entidad en el servicio de origen, tal y como viaja en el evento. */
    @Size(max = 100)
    @Column(name = "source_entity_id", length = 100)
    @ToString.Include
    private String sourceEntityId;

    /** Un proyecto sincronizado no se edita a mano: lo que se cambie aquí lo pisa el siguiente evento. */
    public boolean isSynchronized() {
        return sourceService != null;
    }

}