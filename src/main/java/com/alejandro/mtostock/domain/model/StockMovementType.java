package com.alejandro.mtostock.domain.model;

import java.math.BigDecimal;

public enum StockMovementType {

    ENTRY(1),
    OUTPUT(-1),
    POSITIVE_ADJUSTMENT(1),
    NEGATIVE_ADJUSTMENT(-1),
    INCOMING_TRANSFER(1),
    OUTGOING_TRANSFER(-1);

    private final int stockSign;

    StockMovementType(int stockSign) {
        this.stockSign = stockSign;
    }

    BigDecimal applyTo(Quantity quantity) {
        DomainValidations.requireNonNull(quantity, "quantity");
        return quantity.amount().multiply(BigDecimal.valueOf(stockSign));
    }

}