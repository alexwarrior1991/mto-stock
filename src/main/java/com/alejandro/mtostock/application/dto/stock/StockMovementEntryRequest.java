package com.alejandro.mtostock.application.dto.stock;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Request DTO used to register stock entering a warehouse.
 */
public record StockMovementEntryRequest(
        @NotNull
        UUID materialId,

        @NotNull
        UUID warehouseId,

        UUID supplierId,

        @NotNull
        @Positive
        @Digits(integer = 13, fraction = 6)
        BigDecimal quantity,

        Instant occurredAt,

        @Size(max = 128)
        String externalReference,

        String notes
) {
}