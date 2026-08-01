package com.alejandro.mtostock.application.dto.material;

import java.util.UUID;

/**
 * Lightweight response DTO for referencing a material from another resource.
 */
public record MaterialSummaryResponse(
        UUID id,
        String code,
        String name,
        String unitOfMeasure,
        Boolean active
) {
}