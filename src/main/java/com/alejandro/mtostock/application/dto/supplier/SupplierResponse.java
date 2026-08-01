package com.alejandro.mtostock.application.dto.supplier;

import com.alejandro.mtostock.application.dto.common.AuditMetadataResponse;

import java.util.UUID;

/**
 * Response DTO exposing supplier data without exposing persistence entities.
 */
public record SupplierResponse(
        UUID id,
        String code,
        String name,
        Boolean active,
        AuditMetadataResponse audit
) {
}