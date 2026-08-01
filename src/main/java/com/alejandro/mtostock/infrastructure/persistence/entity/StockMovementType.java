package com.alejandro.mtostock.infrastructure.persistence.entity;

import java.math.BigDecimal;

/**
 * Defines the inventory ledger movement kinds and their stock calculation sign.
 */
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

    BigDecimal applyTo(BigDecimal quantity) {
        return quantity.multiply(BigDecimal.valueOf(stockSign));
    }

}