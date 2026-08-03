package com.alejandro.mtostock.application.service.impl;

import com.alejandro.mtostock.application.exception.AssemblyException;
import com.alejandro.mtostock.application.exception.DuplicateCodeException;
import com.alejandro.mtostock.application.exception.InsufficientStockException;
import com.alejandro.mtostock.application.exception.ReservationException;
import com.alejandro.mtostock.application.exception.ValidationException;
import com.alejandro.mtostock.application.exception.WarehouseException;
import com.alejandro.mtostock.application.service.InventoryValidationService;
import com.alejandro.mtostock.application.service.StockCalculationService;
import com.alejandro.mtostock.infrastructure.persistence.entity.Assembly;
import com.alejandro.mtostock.infrastructure.persistence.entity.Material;
import com.alejandro.mtostock.infrastructure.persistence.entity.Reservation;
import com.alejandro.mtostock.infrastructure.persistence.entity.ReservationStatus;
import com.alejandro.mtostock.infrastructure.persistence.entity.Warehouse;
import com.alejandro.mtostock.infrastructure.persistence.repository.AssemblyRepository;
import com.alejandro.mtostock.infrastructure.persistence.repository.MaterialRepository;
import com.alejandro.mtostock.infrastructure.persistence.repository.ProjectRepository;
import com.alejandro.mtostock.infrastructure.persistence.repository.SupplierRepository;
import com.alejandro.mtostock.infrastructure.persistence.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Centralized implementation for reusable inventory business validations.
 */
@Service
@ConditionalOnBean(MaterialRepository.class)
@RequiredArgsConstructor
class InventoryValidationServiceImpl implements InventoryValidationService {

    private final MaterialRepository materialRepository;
    private final AssemblyRepository assemblyRepository;
    private final WarehouseRepository warehouseRepository;
    private final SupplierRepository supplierRepository;
    private final ProjectRepository projectRepository;
    private final StockCalculationService stockCalculationService;

    @Override
    @Transactional(readOnly = true)
    public void validateMaterialCodeIsUnique(String code, UUID existingId) {
        materialRepository.findByCode(code)
                .filter(material -> !material.getId().equals(existingId))
                .ifPresent(material -> {
                    throw new DuplicateCodeException("Material", code);
                });
    }

    @Override
    @Transactional(readOnly = true)
    public void validateAssemblyCodeIsUnique(String code, UUID existingId) {
        assemblyRepository.findByCode(code)
                .filter(assembly -> !assembly.getId().equals(existingId))
                .ifPresent(assembly -> {
                    throw new DuplicateCodeException("Assembly", code);
                });
    }

    @Override
    @Transactional(readOnly = true)
    public void validateWarehouseCodeIsUnique(String code, UUID existingId) {
        warehouseRepository.findByCode(code)
                .filter(warehouse -> !warehouse.getId().equals(existingId))
                .ifPresent(warehouse -> {
                    throw new DuplicateCodeException("Warehouse", code);
                });
    }

    @Override
    @Transactional(readOnly = true)
    public void validateSupplierCodeIsUnique(String code, UUID existingId) {
        supplierRepository.findByCode(code)
                .filter(supplier -> !supplier.getId().equals(existingId))
                .ifPresent(supplier -> {
                    throw new DuplicateCodeException("Supplier", code);
                });
    }

    @Override
    @Transactional(readOnly = true)
    public void validateProjectCodeIsUnique(String code, UUID existingId) {
        projectRepository.findByCode(code)
                .filter(project -> !project.getId().equals(existingId))
                .ifPresent(project -> {
                    throw new DuplicateCodeException("Project", code);
                });
    }

    @Override
    public void validateActive(Material material) {
        if (!Boolean.TRUE.equals(material.getActive())) {
            throw new ValidationException("Material '%s' is inactive".formatted(material.getCode()));
        }
    }

    @Override
    public void validateActive(Warehouse warehouse) {
        if (!Boolean.TRUE.equals(warehouse.getActive())) {
            throw new WarehouseException("Warehouse '%s' is inactive".formatted(warehouse.getCode()));
        }
    }

    @Override
    public void validateActive(Assembly assembly) {
        if (!Boolean.TRUE.equals(assembly.getActive())) {
            throw new AssemblyException("Assembly '%s' is inactive".formatted(assembly.getCode()));
        }
    }

    @Override
    public void validatePositiveQuantity(BigDecimal quantity) {
        if (quantity == null || quantity.signum() <= 0) {
            throw new ValidationException("Quantity must be greater than zero");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public void validateAvailableStock(UUID materialId, UUID warehouseId, BigDecimal requestedQuantity) {
        validateAvailableStock(materialId, warehouseId, requestedQuantity, BigDecimal.ZERO);
    }

    @Override
    @Transactional(readOnly = true)
    public void validateAvailableStock(UUID materialId, UUID warehouseId, BigDecimal requestedQuantity, BigDecimal alreadyReservedQuantity) {
        validatePositiveQuantity(requestedQuantity);
        BigDecimal available = stockCalculationService.calculateAvailableStock(materialId, warehouseId)
                .add(alreadyReservedQuantity == null ? BigDecimal.ZERO : alreadyReservedQuantity);
        if (available.compareTo(requestedQuantity) < 0) {
            throw new InsufficientStockException(materialId, warehouseId, requestedQuantity, available);
        }
    }

    @Override
    public void validateReservationCanChange(Reservation reservation) {
        if (reservation.getStatus() != ReservationStatus.ACTIVE) {
            throw new ReservationException("Only active reservations can be changed");
        }
    }

    @Override
    public void validateAssemblyHasComponents(Assembly assembly) {
        if (assembly.getComponents() == null || assembly.getComponents().isEmpty()) {
            throw new AssemblyException("Assembly '%s' must contain at least one BOM component".formatted(assembly.getCode()));
        }
    }

    @Override
    public void validateDifferentWarehouses(UUID sourceWarehouseId, UUID targetWarehouseId) {
        if (sourceWarehouseId != null && sourceWarehouseId.equals(targetWarehouseId)) {
            throw new WarehouseException("Source and destination warehouses must be different");
        }
    }
}