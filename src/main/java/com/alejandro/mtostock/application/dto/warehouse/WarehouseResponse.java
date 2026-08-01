package com.alejandro.mtostock.application.dto.warehouse;

import com.alejandro.mtostock.application.dto.common.AuditMetadataResponse;

import java.util.UUID;

/**
 * Response DTO exposing warehouse data without exposing persistence entities.
 */
public record WarehouseResponse(
        UUID id,
        String code,
        String name,
        Boolean active,
        AuditMetadataResponse audit
) {
}