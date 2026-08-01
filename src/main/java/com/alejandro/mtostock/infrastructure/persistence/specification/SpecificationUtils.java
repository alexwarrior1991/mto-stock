package com.alejandro.mtostock.infrastructure.persistence.specification;

import org.springframework.data.jpa.domain.Specification;

import java.util.UUID;

/**
 * Small helper methods for composing null-safe Spring Data JPA Specifications.
 */
public final class SpecificationUtils {

    private SpecificationUtils() {
    }

    public static <T> Specification<T> alwaysTrue() {
        return (root, query, criteriaBuilder) -> criteriaBuilder.conjunction();
    }

    public static <T> Specification<T> equalsBoolean(String attribute, Boolean value) {
        if (value == null) {
            return alwaysTrue();
        }
        return (root, query, criteriaBuilder) -> criteriaBuilder.equal(root.get(attribute), value);
    }

    public static <T> Specification<T> equalsEnum(String attribute, Enum<?> value) {
        if (value == null) {
            return alwaysTrue();
        }
        return (root, query, criteriaBuilder) -> criteriaBuilder.equal(root.get(attribute), value);
    }

    public static <T> Specification<T> associationIdEquals(String association, UUID id) {
        if (id == null) {
            return alwaysTrue();
        }
        return (root, query, criteriaBuilder) -> criteriaBuilder.equal(root.get(association).get("id"), id);
    }

    public static <T> Specification<T> containsIgnoreCase(String attribute, String value) {
        String normalizedValue = normalize(value);
        if (normalizedValue == null) {
            return alwaysTrue();
        }
        return (root, query, criteriaBuilder) -> criteriaBuilder.like(
                criteriaBuilder.lower(root.get(attribute)),
                "%" + normalizedValue.toLowerCase() + "%"
        );
    }

    public static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}