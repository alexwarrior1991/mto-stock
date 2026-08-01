package com.alejandro.mtostock.application.dto.warehouse;

import java.util.UUID;

/**
 * Lightweight response DTO for referencing a warehouse from another resource.
 */
public record WarehouseSummaryResponse(
        UUID id,
        String code,
        String name,
        Boolean active
) {
}