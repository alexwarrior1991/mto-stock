package com.alejandro.mtostock.infrastructure.persistence.specification;

import com.alejandro.mtostock.infrastructure.persistence.entity.Supplier;
import org.springframework.data.jpa.domain.Specification;

/**
 * Composable Specifications for supplier catalogue searches.
 */
public final class SupplierSpecification {

    private SupplierSpecification() {
    }

    /** The free-text search of the catalogue: code or name, case-insensitive; blank matches everything. */
    public static Specification<Supplier> codeOrNameContains(String search) {
        return SpecificationUtils.containsIgnoreCaseAny(search, "code", "name");
    }

    public static Specification<Supplier> activeEquals(Boolean active) {
        return SpecificationUtils.equalsBoolean("active", active);
    }
}
