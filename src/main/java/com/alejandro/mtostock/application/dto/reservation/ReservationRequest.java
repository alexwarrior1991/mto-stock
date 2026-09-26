package com.alejandro.mtostock.application.dto.reservation;

import com.alejandro.mtostock.application.dto.common.IdempotencyIgnored;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Request DTO used to reserve material stock for a project.
 */
public record ReservationRequest(
        @NotNull
        UUID materialId,

        @NotNull
        UUID warehouseId,

        @NotNull
        UUID projectId,

        @NotNull
        @Positive
        @Digits(integer = 13, fraction = 6)
        BigDecimal quantity,

        @IdempotencyIgnored
        Instant reservedAt
) {
}