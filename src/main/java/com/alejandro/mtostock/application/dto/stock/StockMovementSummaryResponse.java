package com.alejandro.mtostock.application.dto.stock;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Lightweight response DTO for referencing a stock movement from another movement.
 */
public record StockMovementSummaryResponse(
        UUID id,
        StockMovementTypeDto type,
        BigDecimal quantity,
        Instant occurredAt,
        String externalReference
) {
}