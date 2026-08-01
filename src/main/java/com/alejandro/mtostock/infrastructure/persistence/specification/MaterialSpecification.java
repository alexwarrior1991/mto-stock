package com.alejandro.mtostock.infrastructure.persistence.specification;

import com.alejandro.mtostock.infrastructure.persistence.entity.Material;
import com.alejandro.mtostock.infrastructure.persistence.entity.Reservation;
import com.alejandro.mtostock.infrastructure.persistence.entity.ReservationStatus;
import com.alejandro.mtostock.infrastructure.persistence.entity.StockMovement;
import com.alejandro.mtostock.infrastructure.persistence.entity.StockMovementType;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Composable Specifications for material catalogue searches and movement-derived stock filters.
 */
public final class MaterialSpecification {

    private MaterialSpecification() {
    }

    public static Specification<Material> codeContains(String code) {
        return SpecificationUtils.containsIgnoreCase("code", code);
    }

    public static Specification<Material> nameContains(String name) {
        return SpecificationUtils.containsIgnoreCase("name", name);
    }

    public static Specification<Material> descriptionContains(String description) {
        return nameContains(description);
    }

    public static Specification<Material> activeEquals(Boolean active) {
        return SpecificationUtils.equalsBoolean("active", active);
    }

    public static Specification<Material> storedInWarehouse(UUID warehouseId) {
        if (warehouseId == null) {
            return SpecificationUtils.alwaysTrue();
        }
        return (root, query, criteriaBuilder) -> {
            Subquery<UUID> stockMovementSubquery = query.subquery(UUID.class);
            Root<StockMovement> stockMovement = stockMovementSubquery.from(StockMovement.class);
            stockMovementSubquery.select(stockMovement.get("material").get("id"))
                    .where(
                            criteriaBuilder.equal(stockMovement.get("material"), root),
                            criteriaBuilder.equal(stockMovement.get("warehouse").get("id"), warehouseId)
                    );
            return criteriaBuilder.exists(stockMovementSubquery);
        };
    }

    public static Specification<Material> stockBelowMinimum(UUID warehouseId) {
        return (root, query, criteriaBuilder) -> {
            Subquery<BigDecimal> stockMovementQuantity = query.subquery(BigDecimal.class);
            Root<StockMovement> stockMovement = stockMovementQuantity.from(StockMovement.class);
            Expression<BigDecimal> movementQuantity = stockMovement.get("quantity");
            Expression<BigDecimal> signedMovementQuantity = criteriaBuilder.<BigDecimal>selectCase()
                    .when(stockMovement.get("type").in(
                            StockMovementType.ENTRY,
                            StockMovementType.POSITIVE_ADJUSTMENT,
                            StockMovementType.INCOMING_TRANSFER
                    ), movementQuantity)
                    .otherwise(criteriaBuilder.neg(movementQuantity));
            stockMovementQuantity.select(criteriaBuilder.coalesce(criteriaBuilder.sum(signedMovementQuantity), BigDecimal.ZERO))
                    .where(materialWarehousePredicates(root, stockMovement, warehouseId, criteriaBuilder));

            Subquery<BigDecimal> activeReservationQuantity = query.subquery(BigDecimal.class);
            Root<Reservation> reservation = activeReservationQuantity.from(Reservation.class);
            activeReservationQuantity.select(criteriaBuilder.coalesce(criteriaBuilder.sum(reservation.get("quantity")), BigDecimal.ZERO))
                    .where(reservationPredicates(root, reservation, warehouseId, criteriaBuilder));

            Expression<BigDecimal> availableQuantity = criteriaBuilder.diff(stockMovementQuantity, activeReservationQuantity);
            return criteriaBuilder.lessThan(availableQuantity, root.get("minimumStockLevel"));
        };
    }

    private static Predicate[] materialWarehousePredicates(
            Root<Material> material,
            Root<StockMovement> stockMovement,
            UUID warehouseId,
            CriteriaBuilder criteriaBuilder
    ) {
        Predicate materialPredicate = criteriaBuilder.equal(stockMovement.get("material"), material);
        if (warehouseId == null) {
            return new Predicate[]{materialPredicate};
        }
        return new Predicate[]{
                materialPredicate,
                criteriaBuilder.equal(stockMovement.get("warehouse").get("id"), warehouseId)
        };
    }

    private static Predicate[] reservationPredicates(
            Root<Material> material,
            Root<Reservation> reservation,
            UUID warehouseId,
            CriteriaBuilder criteriaBuilder
    ) {
        Predicate materialPredicate = criteriaBuilder.equal(reservation.get("material"), material);
        Predicate activePredicate = criteriaBuilder.equal(reservation.get("status"), ReservationStatus.ACTIVE);
        if (warehouseId == null) {
            return new Predicate[]{materialPredicate, activePredicate};
        }
        return new Predicate[]{
                materialPredicate,
                activePredicate,
                criteriaBuilder.equal(reservation.get("warehouse").get("id"), warehouseId)
        };
    }
}