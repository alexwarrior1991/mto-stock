package com.alejandro.mtostock.application.dto.material;

import com.alejandro.mtostock.application.dto.warehouse.WarehouseSummaryResponse;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Response DTO representing calculated material stock for a warehouse.
 */
public record MaterialStockResponse(
        MaterialSummaryResponse material,
        WarehouseSummaryResponse warehouse,
        BigDecimal onHandQuantity,
        BigDecimal activeReservedQuantity,
        BigDecimal availableQuantity,
        BigDecimal minimumStockLevel,
        Boolean lowStock,
        Instant calculatedAt
) {
}