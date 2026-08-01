package com.alejandro.mtostock.application.dto.stock;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Request DTO used to move stock between two different warehouses.
 */
public record StockMovementTransferRequest(
        @NotNull
        UUID materialId,

        @NotNull
        UUID sourceWarehouseId,

        @NotNull
        UUID targetWarehouseId,

        @NotNull
        @Positive
        @Digits(integer = 13, fraction = 6)
        BigDecimal quantity,

        Instant occurredAt,

        @Size(max = 128)
        String externalReference,

        String notes
) {

    @AssertTrue(message = "sourceWarehouseId and targetWarehouseId must be different")
    public boolean hasDifferentWarehouses() {
        return sourceWarehouseId == null || targetWarehouseId == null || !sourceWarehouseId.equals(targetWarehouseId);
    }

}