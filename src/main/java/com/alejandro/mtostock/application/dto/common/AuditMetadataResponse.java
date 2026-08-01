package com.alejandro.mtostock.application.dto.common;

import java.time.Instant;

/**
 * Response DTO containing audit metadata common to persisted API resources.
 */
public record AuditMetadataResponse(
        Instant createdAt,
        Instant updatedAt,
        String createdBy,
        String updatedBy
) {
}