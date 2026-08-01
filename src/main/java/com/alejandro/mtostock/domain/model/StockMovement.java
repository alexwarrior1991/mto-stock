package com.alejandro.mtostock.domain.model;

import java.math.BigDecimal;
import java.time.Instant;

public record StockMovement(
        Material material,
        Warehouse warehouse,
        StockMovementType type,
        Quantity quantity,
        Instant occurredAt
) {

    public StockMovement {
        material = DomainValidations.requireNonNull(material, "material");
        warehouse = DomainValidations.requireNonNull(warehouse, "warehouse");
        type = DomainValidations.requireNonNull(type, "stock movement type");
        quantity = DomainValidations.requireNonNull(quantity, "quantity");
        occurredAt = DomainValidations.requireNonNull(occurredAt, "occurred at");
        if (!quantity.isPositive()) {
            throw new IllegalArgumentException("stock movement quantity must be greater than zero");
        }
    }

    public static StockMovement entry(Material material, Warehouse warehouse, Quantity quantity) {
        return new StockMovement(material, warehouse, StockMovementType.ENTRY, quantity, Instant.now());
    }

    public static StockMovement output(Material material, Warehouse warehouse, Quantity quantity) {
        return new StockMovement(material, warehouse, StockMovementType.OUTPUT, quantity, Instant.now());
    }

    public static StockMovement positiveAdjustment(Material material, Warehouse warehouse, Quantity quantity) {
        return new StockMovement(material, warehouse, StockMovementType.POSITIVE_ADJUSTMENT, quantity, Instant.now());
    }

    public static StockMovement negativeAdjustment(Material material, Warehouse warehouse, Quantity quantity) {
        return new StockMovement(material, warehouse, StockMovementType.NEGATIVE_ADJUSTMENT, quantity, Instant.now());
    }

    public static StockMovement incomingTransfer(Material material, Warehouse warehouse, Quantity quantity) {
        return new StockMovement(material, warehouse, StockMovementType.INCOMING_TRANSFER, quantity, Instant.now());
    }

    public static StockMovement outgoingTransfer(Material material, Warehouse warehouse, Quantity quantity) {
        return new StockMovement(material, warehouse, StockMovementType.OUTGOING_TRANSFER, quantity, Instant.now());
    }

    BigDecimal signedQuantity() {
        return type.applyTo(quantity);
    }

}