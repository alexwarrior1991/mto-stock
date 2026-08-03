package com.alejandro.mtostock.application.exception;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Raised when a use case would make calculated available stock negative.
 */
public class InsufficientStockException extends BusinessException {

    public InsufficientStockException(UUID materialId, UUID warehouseId, BigDecimal requested, BigDecimal available) {
        super("Insufficient stock for material %s in warehouse %s: requested %s, available %s"
                .formatted(materialId, warehouseId, requested, available));
    }
}