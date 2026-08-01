package com.alejandro.mtostock.application.dto.assembly;

import com.alejandro.mtostock.application.dto.warehouse.WarehouseSummaryResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Response DTO for calculated assembly availability based on BOM component stock.
 */
public record AssemblyAvailabilityResponse(
        AssemblySummaryResponse assembly,
        WarehouseSummaryResponse warehouse,
        BigDecimal availableQuantity,
        List<AssemblyAvailabilityComponentResponse> components,
        Instant calculatedAt
) {
}