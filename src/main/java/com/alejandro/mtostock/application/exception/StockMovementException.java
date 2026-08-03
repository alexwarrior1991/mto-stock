package com.alejandro.mtostock.application.exception;

/**
 * Raised when an append-only stock movement command violates inventory movement rules.
 */
public class StockMovementException extends BusinessException {

    public StockMovementException(String message) {
        super(message);
    }
}