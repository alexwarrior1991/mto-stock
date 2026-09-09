package com.alejandro.mtostock.application.service;

import com.alejandro.mtostock.application.dto.material.MaterialStockResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Domain service responsible only for movement-derived stock calculations.
 *
 * <p>En los tres primeros métodos {@code warehouseId} es opcional: con un almacén se responde por
 * ese almacén y con {@code null} por todos. No hay un método aparte para cada caso — los hubo,
 * {@code calculateWarehouseStock} y {@code calculateGlobalStock}, y no llegó a llamarlos nadie:
 * eran {@code calculatePhysicalStock} con el argumento ya decidido.</p>
 */
public interface StockCalculationService {

    BigDecimal calculatePhysicalStock(UUID materialId, UUID warehouseId);

    BigDecimal calculateReservedStock(UUID materialId, UUID warehouseId);

    BigDecimal calculateAvailableStock(UUID materialId, UUID warehouseId);

    BigDecimal calculateHistoricalStock(UUID materialId, UUID warehouseId, Instant asOf);

    MaterialStockResponse calculateMaterialStock(UUID materialId, UUID warehouseId);
}