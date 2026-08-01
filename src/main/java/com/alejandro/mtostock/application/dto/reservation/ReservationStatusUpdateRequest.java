package com.alejandro.mtostock.application.dto.reservation;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * Request DTO used to release or cancel an active reservation.
 */
public record ReservationStatusUpdateRequest(
        @NotNull
        ReservationStatusDto status,

        Instant releasedAt
) {

    @AssertTrue(message = "status must be RELEASED or CANCELLED")
    public boolean hasTerminalStatus() {
        return status == null || ReservationStatusDto.RELEASED == status || ReservationStatusDto.CANCELLED == status;
    }

}