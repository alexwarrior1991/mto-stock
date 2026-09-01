package com.alejandro.mtostock.infrastructure.persistence.specification;

import com.alejandro.mtostock.infrastructure.persistence.entity.InventoryBalance;
import com.alejandro.mtostock.infrastructure.persistence.entity.Material;
import com.alejandro.mtostock.infrastructure.persistence.entity.StockMovement;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Composable Specifications for material catalogue searches and projection-derived stock filters.
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

    /**
     * Matches materials whose projected available stock is below their configured minimum level.
     *
     * <p>Reads {@code inventory_balance}, the same projection {@code StockCalculationService} serves stock
     * reads from, so this filter cannot drift from the stock reported for an individual material.
     */
    public static Specification<Material> stockBelowMinimum(UUID warehouseId) {
        return (root, query, criteriaBuilder) -> {
            Subquery<BigDecimal> availableQuantity = query.subquery(BigDecimal.class);
            Root<InventoryBalance> inventoryBalance = availableQuantity.from(InventoryBalance.class);
            availableQuantity
                    .select(criteriaBuilder.coalesce(criteriaBuilder.sum(inventoryBalance.get("availableQuantity")), BigDecimal.ZERO))
                    .where(balancePredicates(root, inventoryBalance, warehouseId, criteriaBuilder));
            return criteriaBuilder.lessThan(availableQuantity, root.get("minimumStockLevel"));
        };
    }

    private static Predicate[] balancePredicates(
            Root<Material> material,
            Root<InventoryBalance> inventoryBalance,
            UUID warehouseId,
            CriteriaBuilder criteriaBuilder
    ) {
        Predicate materialPredicate = criteriaBuilder.equal(inventoryBalance.get("material").get("id"), material.get("id"));
        if (warehouseId == null) {
            return new Predicate[]{materialPredicate};
        }
        return new Predicate[]{
                materialPredicate,
                criteriaBuilder.equal(inventoryBalance.get("warehouse").get("id"), warehouseId)
        };
    }
}
