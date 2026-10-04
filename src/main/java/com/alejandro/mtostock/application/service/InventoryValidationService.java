package com.alejandro.mtostock.application.service;

import com.alejandro.mtostock.infrastructure.persistence.entity.Assembly;
import com.alejandro.mtostock.infrastructure.persistence.entity.Material;
import com.alejandro.mtostock.infrastructure.persistence.entity.Reservation;
import com.alejandro.mtostock.infrastructure.persistence.entity.Warehouse;

import java.math.BigDecimal;
import java.util.List;
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
    void validateReservationCanChange(Reservation reservation);
    void validateAssemblyHasComponents(Assembly assembly);

    /**
     * Un material aparece una sola vez en la lista de materiales de un conjunto (422 {@code ASM-001}).
     * La petición ya lo comprueba ({@code @UniqueComponentMaterials}, 400); esto es para quien llama
     * al servicio sin pasar por el controlador. Los {@code null} no cuentan: los rechaza su campo.
     */
    void validateAssemblyComponentsAreDistinct(List<UUID> materialIds);

    void validateDifferentWarehouses(UUID sourceWarehouseId, UUID targetWarehouseId);
}