package com.alejandro.mtostock.application.service;

import com.alejandro.mtostock.infrastructure.persistence.entity.InventoryBalance;
import com.alejandro.mtostock.infrastructure.persistence.entity.Material;
import com.alejandro.mtostock.infrastructure.persistence.entity.Warehouse;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Application service responsible for every current stock balance mutation.
 */
public interface InventoryBalanceService {

    void increasePhysical(UUID materialId, UUID warehouseId, BigDecimal quantity);

    void decreasePhysicalAndAvailable(UUID materialId, UUID warehouseId, BigDecimal quantity);

    void reserve(UUID materialId, UUID warehouseId, BigDecimal quantity);

    void releaseReserved(UUID materialId, UUID warehouseId, BigDecimal quantity);

    void consumeReserved(UUID materialId, UUID warehouseId, BigDecimal quantity);

    InventoryBalance findOrCreateBalance(Material material, Warehouse warehouse);
}