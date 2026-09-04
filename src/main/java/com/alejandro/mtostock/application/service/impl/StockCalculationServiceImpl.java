package com.alejandro.mtostock.application.service.impl;

import com.alejandro.mtostock.application.dto.material.MaterialStockResponse;
import com.alejandro.mtostock.application.exception.NotFoundException;
import com.alejandro.mtostock.application.mapper.MaterialMapper;
import com.alejandro.mtostock.application.mapper.WarehouseMapper;
import com.alejandro.mtostock.application.service.StockCalculationService;
import com.alejandro.mtostock.infrastructure.persistence.entity.Material;
import com.alejandro.mtostock.infrastructure.persistence.entity.StockMovementType;
import com.alejandro.mtostock.infrastructure.persistence.entity.Warehouse;
import com.alejandro.mtostock.infrastructure.persistence.repository.InventoryBalanceRepository;
import com.alejandro.mtostock.infrastructure.persistence.repository.MaterialRepository;
import com.alejandro.mtostock.infrastructure.persistence.repository.StockMovementRepository;
import com.alejandro.mtostock.infrastructure.persistence.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Calculates current inventory balances from the balance projection and historical stock from movements.
 */
@Service
@RequiredArgsConstructor
class StockCalculationServiceImpl implements StockCalculationService {

    private static final Set<StockMovementType> POSITIVE_MOVEMENT_TYPES = Set.of(
            StockMovementType.ENTRY,
            StockMovementType.POSITIVE_ADJUSTMENT,
            StockMovementType.INCOMING_TRANSFER
    );

    private final InventoryBalanceRepository inventoryBalanceRepository;
    private final StockMovementRepository stockMovementRepository;
    private final MaterialRepository materialRepository;
    private final WarehouseRepository warehouseRepository;
    private final MaterialMapper materialMapper;
    private final WarehouseMapper warehouseMapper;

    @Override
    @Transactional(readOnly = true)
    public BigDecimal calculatePhysicalStock(UUID materialId, UUID warehouseId) {
        return inventoryBalanceRepository.calculatePhysicalQuantity(materialId, warehouseId, BigDecimal.ZERO);
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal calculateReservedStock(UUID materialId, UUID warehouseId) {
        return inventoryBalanceRepository.calculateReservedQuantity(materialId, warehouseId, BigDecimal.ZERO);
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal calculateAvailableStock(UUID materialId, UUID warehouseId) {
        return inventoryBalanceRepository.calculateAvailableQuantity(materialId, warehouseId, BigDecimal.ZERO);
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal calculateWarehouseStock(UUID materialId, UUID warehouseId) {
        return calculatePhysicalStock(materialId, warehouseId);
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal calculateGlobalStock(UUID materialId) {
        return calculatePhysicalStock(materialId, null);
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal calculateHistoricalStock(UUID materialId, UUID warehouseId, Instant asOf) {
        return stockMovementRepository.calculateSignedQuantity(
                materialId,
                warehouseId,
                asOf,
                POSITIVE_MOVEMENT_TYPES,
                BigDecimal.ZERO
        );
    }

    @Override
    @Transactional(readOnly = true)
    public MaterialStockResponse calculateMaterialStock(UUID materialId, UUID warehouseId) {
        Material material = materialRepository.findById(materialId)
                .orElseThrow(() -> new NotFoundException("Material", materialId));
        Warehouse warehouse = warehouseId == null ? null : warehouseRepository.findById(warehouseId)
                .orElseThrow(() -> new NotFoundException("Warehouse", warehouseId));
        BigDecimal physical = calculatePhysicalStock(materialId, warehouseId);
        BigDecimal reserved = calculateReservedStock(materialId, warehouseId);
        BigDecimal available = calculateAvailableStock(materialId, warehouseId);
        BigDecimal minimumStockLevel = material.getMinimumStockLevel();
        return new MaterialStockResponse(
                materialMapper.toSummaryResponse(material),
                warehouse == null ? null : warehouseMapper.toSummaryResponse(warehouse),
                physical,
                reserved,
                available,
                minimumStockLevel,
                available.compareTo(minimumStockLevel) < 0,
                Instant.now()
        );
    }
}