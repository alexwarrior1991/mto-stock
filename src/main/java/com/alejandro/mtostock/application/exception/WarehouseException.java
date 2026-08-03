package com.alejandro.mtostock.application.exception;

/**
 * Raised when a warehouse operation violates inventory rules.
 */
public class WarehouseException extends BusinessException {

    public WarehouseException(String message) {
        super(message);
    }
}