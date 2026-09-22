package com.alejandro.mtostock.infrastructure.persistence.specification;

import com.alejandro.mtostock.infrastructure.persistence.entity.Warehouse;
import org.springframework.data.jpa.domain.Specification;

/**
 * Composable Specifications for warehouse catalogue searches.
 */
public final class WarehouseSpecification {

    private WarehouseSpecification() {
    }

    /** The free-text search of the catalogue: code or name, case-insensitive; blank matches everything. */
    public static Specification<Warehouse> codeOrNameContains(String search) {
        return SpecificationUtils.containsIgnoreCaseAny(search, "code", "name");
    }

    public static Specification<Warehouse> activeEquals(Boolean active) {
        return SpecificationUtils.equalsBoolean("active", active);
    }
}
