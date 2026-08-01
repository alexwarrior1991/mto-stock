package com.alejandro.mtostock.application.dto.project;

import java.util.UUID;

/**
 * Lightweight response DTO for referencing a project from reservations and movements.
 */
public record ProjectSummaryResponse(
        UUID id,
        String code,
        String name,
        Boolean active
) {
}