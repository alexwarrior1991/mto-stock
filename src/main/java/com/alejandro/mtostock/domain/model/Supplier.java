package com.alejandro.mtostock.domain.model;

public record Supplier(String code, String name, boolean active) {

    public Supplier {
        code = DomainValidations.requireText(code, "supplier code");
        name = DomainValidations.requireText(name, "supplier name");
    }

}