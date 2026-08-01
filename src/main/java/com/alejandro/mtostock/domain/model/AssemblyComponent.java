package com.alejandro.mtostock.domain.model;

public record AssemblyComponent(Material material, Quantity quantity) {

    public AssemblyComponent {
        material = DomainValidations.requireNonNull(material, "material");
        quantity = DomainValidations.requireNonNull(quantity, "quantity");
        if (!quantity.isPositive()) {
            throw new IllegalArgumentException("assembly component quantity must be greater than zero");
        }
    }

}