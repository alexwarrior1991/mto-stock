package com.alejandro.mtostock.application.dto.assembly;

import com.alejandro.mtostock.application.dto.material.MaterialSummaryResponse;

import java.math.BigDecimal;

/**
 * Response DTO describing how one BOM component contributes to assembly availability.
 */
public record AssemblyAvailabilityComponentResponse(
        MaterialSummaryResponse material,
        BigDecimal requiredQuantityPerAssembly,
        BigDecimal onHandQuantity,
        BigDecimal activeReservedQuantity,
        BigDecimal availableQuantity,
        BigDecimal producibleAssemblyQuantity,
        Boolean limitingComponent
) {
}