package com.alejandro.mtostock.infrastructure.persistence.specification;

import com.alejandro.mtostock.infrastructure.persistence.entity.Project;
import org.springframework.data.jpa.domain.Specification;

/**
 * Composable Specifications for project catalogue searches.
 */
public final class ProjectSpecification {

    private ProjectSpecification() {
    }

    /** The free-text search of the catalogue: code or name, case-insensitive; blank matches everything. */
    public static Specification<Project> codeOrNameContains(String search) {
        return SpecificationUtils.containsIgnoreCaseAny(search, "code", "name");
    }

    public static Specification<Project> activeEquals(Boolean active) {
        return SpecificationUtils.equalsBoolean("active", active);
    }
}
