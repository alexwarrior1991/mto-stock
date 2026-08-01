package com.alejandro.mtostock.domain.model;

public record Material(String code, String name, String unitOfMeasure, Quantity minimumStockLevel, boolean active) {

    public Material {
        code = DomainValidations.requireText(code, "material code");
        name = DomainValidations.requireText(name, "material name");
        unitOfMeasure = DomainValidations.requireText(unitOfMeasure, "unit of measure");
        minimumStockLevel = DomainValidations.requireNonNull(minimumStockLevel, "minimum stock level");
    }

}