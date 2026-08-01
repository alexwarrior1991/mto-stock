package com.alejandro.mtostock.application.dto.stock;

/**
 * API enum describing stock movement categories used by stock calculations.
 */
public enum StockMovementTypeDto {

    ENTRY,
    OUTPUT,
    POSITIVE_ADJUSTMENT,
    NEGATIVE_ADJUSTMENT,
    INCOMING_TRANSFER,
    OUTGOING_TRANSFER

}