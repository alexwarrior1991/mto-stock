package com.alejandro.mtostock.domain.model;

import java.util.List;
import java.util.Map;

public record Assembly(String code, String name, List<AssemblyComponent> components, boolean active) {

    public Assembly {
        code = DomainValidations.requireText(code, "assembly code");
        name = DomainValidations.requireText(name, "assembly name");
        components = List.copyOf(DomainValidations.requireNonNull(components, "assembly components"));
        if (components.isEmpty()) {
            throw new IllegalArgumentException("assembly must contain at least one component");
        }
    }

    public long availableUnits(Map<Material, Quantity> componentStock) {
        DomainValidations.requireNonNull(componentStock, "component stock");
        return components.stream()
                .mapToLong(component -> componentStock
                        .getOrDefault(component.material(), Quantity.zero())
                        .wholeUnitsAvailableFor(component.quantity()))
                .min()
                .orElse(0);
    }

}