package com.alejandro.mtostock.domain.model;

public record Reservation(Material material, Warehouse warehouse, Project project, Quantity quantity, ReservationStatus status) {

    public Reservation {
        material = DomainValidations.requireNonNull(material, "material");
        warehouse = DomainValidations.requireNonNull(warehouse, "warehouse");
        project = DomainValidations.requireNonNull(project, "project");
        quantity = DomainValidations.requireNonNull(quantity, "quantity");
        status = DomainValidations.requireNonNull(status, "reservation status");
        if (!quantity.isPositive()) {
            throw new IllegalArgumentException("reservation quantity must be greater than zero");
        }
    }

    public boolean isActive() {
        return status == ReservationStatus.ACTIVE;
    }

}