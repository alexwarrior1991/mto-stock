package com.alejandro.mtostock.application.dto.supplier;

import java.util.UUID;

/**
 * Lightweight response DTO for referencing a supplier from movement data.
 */
public record SupplierSummaryResponse(
        UUID id,
        String code,
        String name,
        Boolean active
) {
}