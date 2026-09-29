package com.alejandro.mtostock.application.service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Application service responsible for every current stock balance mutation.
 */
public interface InventoryBalanceService {

    void increasePhysical(UUID materialId, UUID warehouseId, BigDecimal quantity);

    void decreasePhysicalAndAvailable(UUID materialId, UUID warehouseId, BigDecimal quantity);

    void reserve(UUID materialId, UUID warehouseId, BigDecimal quantity);

    /**
     * Mueve stock fisico entre dos almacenes del mismo material en una sola operacion: el total del
     * material no cambia, asi que no puede cruzar por debajo del minimo, y el bloqueo del material se
     * toma una vez para las dos filas.
     */
    void transfer(UUID materialId, UUID sourceWarehouseId, UUID targetWarehouseId, BigDecimal quantity);

    void releaseReserved(UUID materialId, UUID warehouseId, BigDecimal quantity);

    void consumeReserved(UUID materialId, UUID warehouseId, BigDecimal quantity);
}