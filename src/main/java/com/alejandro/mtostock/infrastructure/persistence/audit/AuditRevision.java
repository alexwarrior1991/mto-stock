package com.alejandro.mtostock.infrastructure.persistence.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.envers.RevisionEntity;
import org.hibernate.envers.RevisionNumber;
import org.hibernate.envers.RevisionTimestamp;

import java.time.Instant;

/**
 * Una revisión: el «quién, cuándo y desde dónde» de una transacción que cambió datos auditados.
 *
 * <p>Envers escribe una fila aquí por <b>transacción</b> que toca alguna entidad anotada con
 * {@code @Audited}, y una fila en cada {@code <tabla>_aud} por entidad cambiada dentro de ella. El
 * reparto es deliberado: la identidad del autor se guarda una vez, no repetida en cada fila de cada
 * tabla gemela. Por eso las tablas {@code _aud} no llevan {@code created_by} / {@code updated_by}.</p>
 *
 * <p>Declarar esta clase sustituye por completo a la tabla {@code REVINFO} que Envers crearía por su
 * cuenta; el nombre {@code audit_revision} es el mismo que usa {@code mto-configuration}, para que
 * las dos mitades del dominio se auditen igual.</p>
 *
 * <p>Se mapea con Envers, pero no es un dato de negocio: no tiene gemela en {@code domain.model}, no
 * la toca ningún mapper y no sale por la API. Por eso vive aquí y no en
 * {@code infrastructure.persistence.entity}.</p>
 */
@Entity
@Table(name = "audit_revision")
@RevisionEntity(AuditRevisionListener.class)
@Getter
@Setter
public class AuditRevision {

    /**
     * {@code allocationSize = 1} a propósito. Con el valor por defecto (50) Hibernate usa un
     * optimizador agrupado cuyo {@code increment by} tiene que coincidir exactamente con el de la
     * secuencia en la migración, y una discrepancia ahí no se ve hasta que se saltan revisiones. El
     * coste de ir de uno en uno es un {@code nextval} por transacción que toque datos auditados, y
     * el camino caliente —{@code stock_movement}— no está auditado.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "audit_revision_generator")
    @SequenceGenerator(name = "audit_revision_generator", sequenceName = "audit_revision_seq", allocationSize = 1)
    @RevisionNumber
    @Column(name = "id", nullable = false)
    private int id;

    /**
     * Milisegundos desde epoch, no {@code Instant}/{@code timestamptz}. Es el tipo que
     * {@code @RevisionTimestamp} admite en cualquier versión de Envers; un tipo que Envers rechace no
     * da un aviso, impide arrancar. {@link #getRevisionInstant()} devuelve lo que se quiere leer.
     */
    @RevisionTimestamp
    @Column(name = "timestamp", nullable = false)
    private long timestamp;

    /**
     * El mismo actor que va a {@code updated_by}, resuelto por
     * {@code AuditActorResolver}: puede ser {@code system} o {@code unknown}, y la diferencia
     * entre los dos significa aquí lo mismo que allí.
     */
    @Column(name = "username", length = 100)
    private String username;

    /** El {@code sub} del token: sobrevive a que alguien se renombre en Keycloak. */
    @Column(name = "user_id", length = 100)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 20)
    private AuditRevisionSource source;

    /** Cabecera {@code X-Correlation-Id} si vino por HTTP, id del mensaje si vino de RabbitMQ. */
    @Column(name = "correlation_id", length = 200)
    private String correlationId;

    @Column(name = "ip_address", length = 100)
    private String ipAddress;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @Column(name = "request_method", length = 20)
    private String requestMethod;

    @Column(name = "request_uri", length = 500)
    private String requestUri;

    @Transient
    public Instant getRevisionInstant() {
        return Instant.ofEpochMilli(timestamp);
    }
}
