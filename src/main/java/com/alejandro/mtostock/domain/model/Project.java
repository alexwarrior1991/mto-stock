package com.alejandro.mtostock.domain.model;

public record Project(String code, String name, boolean active) {

    public Project {
        code = DomainValidations.requireText(code, "project code");
        name = DomainValidations.requireText(name, "project name");
    }

}