package com.alejandro.mtostock.application.service;

import com.alejandro.mtostock.application.dto.material.MaterialStockResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Domain service responsible only for movement-derived stock calculations.
 */
public interface StockCalculationService {

    BigDecimal calculatePhysicalStock(UUID materialId, UUID warehouseId);

    BigDecimal calculateReservedStock(UUID materialId, UUID warehouseId);

    BigDecimal calculateAvailableStock(UUID materialId, UUID warehouseId);

    BigDecimal calculateWarehouseStock(UUID materialId, UUID warehouseId);

    BigDecimal calculateGlobalStock(UUID materialId);

    BigDecimal calculateHistoricalStock(UUID materialId, UUID warehouseId, Instant asOf);

    MaterialStockResponse calculateMaterialStock(UUID materialId, UUID warehouseId);
}