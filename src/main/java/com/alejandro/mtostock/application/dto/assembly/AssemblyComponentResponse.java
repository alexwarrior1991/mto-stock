package com.alejandro.mtostock.application.dto.assembly;

import com.alejandro.mtostock.application.dto.common.AuditMetadataResponse;
import com.alejandro.mtostock.application.dto.material.MaterialSummaryResponse;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Response DTO representing a bill of materials component line.
 */
public record AssemblyComponentResponse(
        UUID id,
        MaterialSummaryResponse material,
        BigDecimal quantity,
        AuditMetadataResponse audit
) {
}