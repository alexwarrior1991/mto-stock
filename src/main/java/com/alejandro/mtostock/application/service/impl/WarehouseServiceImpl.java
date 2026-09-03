package com.alejandro.mtostock.application.service.impl;

import com.alejandro.mtostock.application.dto.common.PageResponse;
import com.alejandro.mtostock.application.dto.material.MaterialStockResponse;
import com.alejandro.mtostock.application.dto.stock.StockMovementResponse;
import com.alejandro.mtostock.application.dto.stock.StockMovementTransferRequest;
import com.alejandro.mtostock.application.dto.warehouse.WarehouseRequest;
import com.alejandro.mtostock.application.dto.warehouse.WarehouseResponse;
import com.alejandro.mtostock.application.dto.warehouse.WarehouseUpdateRequest;
import com.alejandro.mtostock.application.exception.NotFoundException;
import com.alejandro.mtostock.application.mapper.StockMovementMapper;
import com.alejandro.mtostock.application.mapper.WarehouseMapper;
import com.alejandro.mtostock.application.service.InventoryValidationService;
import com.alejandro.mtostock.application.service.StockCalculationService;
import com.alejandro.mtostock.application.service.TransferService;
import com.alejandro.mtostock.application.service.WarehouseService;
import com.alejandro.mtostock.configuration.cache.CacheInvalidator;
import com.alejandro.mtostock.configuration.cache.CacheNames;
import com.alejandro.mtostock.infrastructure.persistence.entity.StockMovement;
import com.alejandro.mtostock.infrastructure.persistence.entity.Warehouse;
import com.alejandro.mtostock.infrastructure.persistence.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Orchestrates warehouse catalogue use cases and delegates stock movements to dedicated engines.
 */
@Service
@ConditionalOnBean(WarehouseRepository.class)
@RequiredArgsConstructor
class WarehouseServiceImpl implements WarehouseService {

    private static final Logger log = LoggerFactory.getLogger(WarehouseServiceImpl.class);

    private final WarehouseRepository warehouseRepository;
    private final WarehouseMapper warehouseMapper;
    private final StockMovementMapper stockMovementMapper;
    private final InventoryValidationService inventoryValidationService;
    private final StockCalculationService stockCalculationService;
    private final TransferService transferService;
    private final CacheInvalidator cacheInvalidator;

    @Override
    @Transactional
    public WarehouseResponse create(WarehouseRequest request) {
        inventoryValidationService.validateWarehouseCodeIsUnique(request.code(), null);
        Warehouse warehouse = warehouseRepository.save(warehouseMapper.toEntity(request));
        log.info("Warehouse created with code {}", warehouse.getCode());
        return warehouseMapper.toResponse(warehouse);
    }

    @Override
    @Transactional
    public WarehouseResponse update(UUID id, WarehouseUpdateRequest request) {
        Warehouse warehouse = warehouseRepository.findById(id).orElseThrow(() -> new NotFoundException("Warehouse", id));
        inventoryValidationService.validateWarehouseCodeIsUnique(request.code(), id);
        warehouseMapper.updateEntity(request, warehouse);
        log.info("Warehouse {} updated", warehouse.getCode());
        cacheInvalidator.evictAfterCommit(CacheNames.WAREHOUSES, id);
        return warehouseMapper.toResponse(warehouse);
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CacheNames.WAREHOUSES, key = "#id")
    public WarehouseResponse findById(UUID id) {
        return warehouseMapper.toResponse(warehouseRepository.findById(id).orElseThrow(() -> new NotFoundException("Warehouse", id)));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<WarehouseResponse> findAll(Pageable pageable) {
        return warehouseMapper.toPageResponse(warehouseRepository.findAll(pageable));
    }

    @Override
    @Transactional(readOnly = true)
    public MaterialStockResponse calculateMaterialStock(UUID warehouseId, UUID materialId) {
        return stockCalculationService.calculateMaterialStock(materialId, warehouseId);
    }

    @Override
    @Transactional
    public List<StockMovementResponse> transfer(StockMovementTransferRequest request) {
        List<StockMovement> movements = transferService.transfer(request);
        return stockMovementMapper.toResponseList(movements);
    }
}