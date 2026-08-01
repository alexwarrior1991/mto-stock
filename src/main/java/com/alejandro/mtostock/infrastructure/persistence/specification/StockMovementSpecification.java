package com.alejandro.mtostock.infrastructure.persistence.specification;

import com.alejandro.mtostock.infrastructure.persistence.entity.StockMovement;
import com.alejandro.mtostock.infrastructure.persistence.entity.StockMovementType;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.UUID;

/**
 * Composable Specifications for append-only stock ledger searches.
 */
public final class StockMovementSpecification {

    private StockMovementSpecification() {
    }

    public static Specification<StockMovement> typeEquals(StockMovementType type) {
        return SpecificationUtils.equalsEnum("type", type);
    }

    public static Specification<StockMovement> warehouseIdEquals(UUID warehouseId) {
        return SpecificationUtils.associationIdEquals("warehouse", warehouseId);
    }

    public static Specification<StockMovement> projectIdEquals(UUID projectId) {
        return SpecificationUtils.associationIdEquals("project", projectId);
    }

    public static Specification<StockMovement> materialIdEquals(UUID materialId) {
        return SpecificationUtils.associationIdEquals("material", materialId);
    }

    public static Specification<StockMovement> occurredAtFrom(Instant fromInclusive) {
        if (fromInclusive == null) {
            return SpecificationUtils.alwaysTrue();
        }
        return (root, query, criteriaBuilder) -> criteriaBuilder.greaterThanOrEqualTo(root.get("occurredAt"), fromInclusive);
    }

    public static Specification<StockMovement> occurredAtTo(Instant toInclusive) {
        if (toInclusive == null) {
            return SpecificationUtils.alwaysTrue();
        }
        return (root, query, criteriaBuilder) -> criteriaBuilder.lessThanOrEqualTo(root.get("occurredAt"), toInclusive);
    }

    public static Specification<StockMovement> createdByContains(String user) {
        return SpecificationUtils.containsIgnoreCase("createdBy", user);
    }
}