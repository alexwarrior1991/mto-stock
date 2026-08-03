package com.alejandro.mtostock.application.service;

import com.alejandro.mtostock.infrastructure.persistence.entity.Assembly;
import com.alejandro.mtostock.infrastructure.persistence.entity.Material;
import com.alejandro.mtostock.infrastructure.persistence.entity.Reservation;
import com.alejandro.mtostock.infrastructure.persistence.entity.Warehouse;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Centralized inventory business validation component shared by use-case services and engines.
 */
public interface InventoryValidationService {

    void validateMaterialCodeIsUnique(String code, UUID existingId);

    void validateAssemblyCodeIsUnique(String code, UUID existingId);

    void validateWarehouseCodeIsUnique(String code, UUID existingId);

    void validateSupplierCodeIsUnique(String code, UUID existingId);

    void validateProjectCodeIsUnique(String code, UUID existingId);
    void validateActive(Material material);
    void validateActive(Warehouse warehouse);
    void validateActive(Assembly assembly);
    void validatePositiveQuantity(BigDecimal quantity);
    void validateAvailableStock(UUID materialId, UUID warehouseId, BigDecimal requestedQuantity);
    void validateAvailableStock(UUID materialId, UUID warehouseId, BigDecimal requestedQuantity, BigDecimal alreadyReservedQuantity);
    void validateReservationCanChange(Reservation reservation);
    void validateAssemblyHasComponents(Assembly assembly);
    void validateDifferentWarehouses(UUID sourceWarehouseId, UUID targetWarehouseId);
}