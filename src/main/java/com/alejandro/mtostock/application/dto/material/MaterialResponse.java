package com.alejandro.mtostock.application.dto.material;

import com.alejandro.mtostock.application.dto.common.AuditMetadataResponse;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Response DTO exposing complete material catalogue data without exposing persistence entities.
 */
public record MaterialResponse(
        UUID id,
        String code,
        String name,
        String unitOfMeasure,
        BigDecimal minimumStockLevel,
        Boolean active,
        AuditMetadataResponse audit
) {
}