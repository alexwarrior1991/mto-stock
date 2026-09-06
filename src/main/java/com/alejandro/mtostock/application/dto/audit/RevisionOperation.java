package com.alejandro.mtostock.application.dto.audit;

/**
 * Qué le pasó a la entidad en una revisión.
 *
 * <p>Es un enum propio y no el {@code RevisionType} de Hibernate: por la API no sale un tipo de una
 * librería de persistencia, y los nombres de Envers ({@code ADD} / {@code MOD} / {@code DEL}) no
 * dicen gran cosa a quien lee la respuesta.</p>
 */
public enum RevisionOperation {

    CREATED,
    UPDATED,
    DELETED
}
