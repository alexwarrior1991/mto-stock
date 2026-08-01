package com.alejandro.mtostock.application.dto.reservation;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Request DTO used to replace editable data of an active reservation.
 */
public record ReservationUpdateRequest(
        @NotNull
        UUID warehouseId,

        @NotNull
        UUID projectId,

        @NotNull
        @Positive
        @Digits(integer = 13, fraction = 6)
        BigDecimal quantity
) {
}