package com.alejandro.mtostock.application.dto.assembly;

import java.util.UUID;

/**
 * Lightweight response DTO for referencing an assembly from calculated views.
 */
public record AssemblySummaryResponse(
        UUID id,
        String code,
        String name,
        Boolean active
) {
}