package com.alejandro.mtostock.application.dto.stock;

import com.alejandro.mtostock.application.dto.common.IdempotencyIgnored;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Request DTO used to register stock leaving a warehouse.
 */
public record StockMovementOutputRequest(
        @NotNull
        UUID materialId,

        @NotNull
        UUID warehouseId,

        UUID projectId,

        UUID reservationId,

        @NotNull
        @Positive
        @Digits(integer = 13, fraction = 6)
        BigDecimal quantity,

        @IdempotencyIgnored
        Instant occurredAt,

        @Size(max = 128)
        String externalReference,

        String notes
) {
}