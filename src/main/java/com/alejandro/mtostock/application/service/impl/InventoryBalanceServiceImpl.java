package com.alejandro.mtostock.application.service.impl;

import com.alejandro.mtostock.application.exception.InsufficientStockException;
import com.alejandro.mtostock.application.exception.ReservationException;
import com.alejandro.mtostock.application.exception.ValidationException;
import com.alejandro.mtostock.application.service.InventoryBalanceService;
import com.alejandro.mtostock.infrastructure.persistence.entity.InventoryBalance;
import com.alejandro.mtostock.infrastructure.persistence.entity.Material;
import com.alejandro.mtostock.infrastructure.persistence.entity.Warehouse;
import com.alejandro.mtostock.infrastructure.persistence.repository.InventoryBalanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.domain.AuditorAware;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Coordinates atomic inventory balance updates and translates failed updates into business errors.
 */
@Service
@ConditionalOnBean(InventoryBalanceRepository.class)
@RequiredArgsConstructor
class InventoryBalanceServiceImpl implements InventoryBalanceService {

    private static final String SYSTEM_ACTOR = "system";
    private static final int MAX_ACTOR_LENGTH = 100;

    private final InventoryBalanceRepository inventoryBalanceRepository;
    private final AuditorAware<String> auditorAware;

    @Override
    @Transactional
    public void increasePhysical(UUID materialId, UUID warehouseId, BigDecimal quantity) {
        validatePositiveQuantity(quantity);
        String actor = currentActor();
        inventoryBalanceRepository.insertZeroBalanceIfMissing(materialId, warehouseId, actor);
        int updatedRows = inventoryBalanceRepository.increasePhysical(materialId, warehouseId, quantity, actor);
        if (updatedRows == 0) {
            throw new ReservationException("Inventory balance could not be increased");
        }
    }

    @Override
    @Transactional
    public void decreasePhysicalAndAvailable(UUID materialId, UUID warehouseId, BigDecimal quantity) {
        validatePositiveQuantity(quantity);
        int updatedRows = inventoryBalanceRepository.decreasePhysicalAndAvailable(materialId, warehouseId, quantity, currentActor());
        if (updatedRows == 0) {
            throw insufficientStock(materialId, warehouseId, quantity);
        }
    }

    @Override
    @Transactional
    public void reserve(UUID materialId, UUID warehouseId, BigDecimal quantity) {
        validatePositiveQuantity(quantity);
        int updatedRows = inventoryBalanceRepository.reserve(materialId, warehouseId, quantity, currentActor());
        if (updatedRows == 0) {
            throw insufficientStock(materialId, warehouseId, quantity);
        }
    }

    @Override
    @Transactional
    public void releaseReserved(UUID materialId, UUID warehouseId, BigDecimal quantity) {
        validatePositiveQuantity(quantity);
        int updatedRows = inventoryBalanceRepository.releaseReserved(materialId, warehouseId, quantity, currentActor());
        if (updatedRows == 0) {
            throw new ReservationException("Reserved stock could not be released");
        }
    }

    @Override
    @Transactional
    public void consumeReserved(UUID materialId, UUID warehouseId, BigDecimal quantity) {
        validatePositiveQuantity(quantity);
        int updatedRows = inventoryBalanceRepository.consumeReserved(materialId, warehouseId, quantity, currentActor());
        if (updatedRows == 0) {
            throw new ReservationException("Reserved stock could not be consumed");
        }
    }

    @Override
    @Transactional
    public InventoryBalance findOrCreateBalance(Material material, Warehouse warehouse) {
        inventoryBalanceRepository.insertZeroBalanceIfMissing(material.getId(), warehouse.getId(), currentActor());
        return inventoryBalanceRepository.findByMaterialIdAndWarehouseId(material.getId(), warehouse.getId())
                .orElseThrow(() -> new ReservationException("Inventory balance could not be created"));
    }

    private String currentActor() {
        return auditorAware.getCurrentAuditor()
                .map(String::trim)
                .filter(actor -> !actor.isBlank())
                .map(this::truncateActor)
                .orElse(SYSTEM_ACTOR);
    }

    private String truncateActor(String actor) {
        if (actor.length() > MAX_ACTOR_LENGTH) {
            return actor.substring(0, MAX_ACTOR_LENGTH);
        }
        return actor;
    }

    private void validatePositiveQuantity(BigDecimal quantity) {
        if (quantity == null || quantity.signum() <= 0) {
            throw new ValidationException("Quantity must be greater than zero");
        }
    }

    private InsufficientStockException insufficientStock(UUID materialId, UUID warehouseId, BigDecimal quantity) {
        BigDecimal available = inventoryBalanceRepository.calculateAvailableQuantity(materialId, warehouseId, BigDecimal.ZERO);
        return new InsufficientStockException(materialId, warehouseId, quantity, available);
    }
}