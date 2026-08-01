package com.alejandro.mtostock.domain.model;

public record Warehouse(String code, String name, boolean active) {

    public Warehouse {
        code = DomainValidations.requireText(code, "warehouse code");
        name = DomainValidations.requireText(name, "warehouse name");
    }

}