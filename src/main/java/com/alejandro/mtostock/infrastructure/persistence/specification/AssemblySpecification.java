package com.alejandro.mtostock.infrastructure.persistence.specification;

import com.alejandro.mtostock.infrastructure.persistence.entity.Assembly;
import org.springframework.data.jpa.domain.Specification;

/**
 * Composable Specifications for virtual assembly searches.
 */
public final class AssemblySpecification {

    private AssemblySpecification() {
    }

    public static Specification<Assembly> codeContains(String code) {
        return SpecificationUtils.containsIgnoreCase("code", code);
    }

    public static Specification<Assembly> nameContains(String name) {
        return SpecificationUtils.containsIgnoreCase("name", name);
    }

    public static Specification<Assembly> activeEquals(Boolean active) {
        return SpecificationUtils.equalsBoolean("active", active);
    }
}