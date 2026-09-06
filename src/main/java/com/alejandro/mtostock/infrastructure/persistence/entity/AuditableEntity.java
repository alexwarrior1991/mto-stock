package com.alejandro.mtostock.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.Hibernate;
import org.hibernate.envers.NotAudited;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Base class for persistent entities that require UUID identity and audit metadata.
 *
 * <h2>Por qué esta clase NO lleva {@code @Audited}</h2>
 *
 * <p>La heredan las diez entidades, y solo siete deben tener historial. Anotarla aquí incluiría
 * {@code StockMovement} —que ya es un libro mayor inmutable, así que duplicarlo no añade ni un
 * dato—, {@code InventoryBalance} e {@code InboxMessage}, que se escriben con SQL nativo y por tanto
 * dejarían una tabla de historial permanentemente vacía: eso no es «no hay auditoría», es una
 * auditoría que dice que nunca cambió nada. Además Envers exigiría tres tablas {@code _aud} que la
 * migración no crea, y con {@code ddl-auto: validate} la aplicación no arrancaría. El
 * {@code @Audited} va entidad por entidad; {@code JpaEntityModelTest} vigila que el reparto no se
 * mueva.</p>
 *
 * <h2>Por qué los cuatro campos de auditoría llevan {@code @NotAudited}</h2>
 *
 * <p>{@code audit_revision} ya guarda quién y cuándo, una vez por revisión en lugar de repetido en
 * cada fila de cada tabla gemela, así que copiarlos sería el mismo dato dos veces y cuatro columnas
 * más que mantener en siete tablas. La anotación es explícita, y no confianza en el valor por
 * defecto, porque lo que hace Envers con las propiedades de un {@code @MappedSuperclass} sin
 * {@code @Audited} ha cambiado entre versiones: dejarlo al criterio de la versión convierte una
 * subida de Hibernate en un fallo de arranque por una columna que sobra o falta en la gemela.</p>
 *
 * <p>El {@code @Id} no lleva {@code @NotAudited} y no debe llevarlo: es la mitad de la clave primaria
 * de la tabla de historial, y Envers lo mapea siempre.</p>
 */
@Getter
@Setter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
@ToString(onlyExplicitlyIncluded = true)
public abstract class AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    @Setter(AccessLevel.PROTECTED)
    @ToString.Include
    private UUID id;

    @NotAudited
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @NotAudited
    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @NotAudited
    @CreatedBy
    @Column(name = "created_by", nullable = false, updatable = false, length = 100)
    private String createdBy;

    @NotAudited
    @LastModifiedBy
    @Column(name = "updated_by", nullable = false, length = 100)
    private String updatedBy;

    @Override
    public final boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) {
            return false;
        }
        AuditableEntity that = (AuditableEntity) other;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public final int hashCode() {
        return Hibernate.getClass(this).hashCode();
    }

}