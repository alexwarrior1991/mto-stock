package com.alejandro.mtostock.application.dto.reservation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Lightweight response DTO for referencing a reservation from movement data.
 */
public record ReservationSummaryResponse(
        UUID id,
        ReservationStatusDto status,
        BigDecimal quantity,
        Instant reservedAt
) {
}