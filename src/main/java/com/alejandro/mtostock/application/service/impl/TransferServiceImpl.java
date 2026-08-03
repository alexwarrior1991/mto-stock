package com.alejandro.mtostock.application.service.impl;

import com.alejandro.mtostock.application.dto.stock.StockMovementTransferRequest;
import com.alejandro.mtostock.application.exception.NotFoundException;
import com.alejandro.mtostock.application.mapper.StockMovementMapper;
import com.alejandro.mtostock.application.service.InventoryValidationService;
import com.alejandro.mtostock.application.service.TransferService;
import com.alejandro.mtostock.infrastructure.persistence.entity.Material;
import com.alejandro.mtostock.infrastructure.persistence.entity.StockMovement;
import com.alejandro.mtostock.infrastructure.persistence.entity.Warehouse;
import com.alejandro.mtostock.infrastructure.persistence.repository.MaterialRepository;
import com.alejandro.mtostock.infrastructure.persistence.repository.StockMovementRepository;
import com.alejandro.mtostock.infrastructure.persistence.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Creates paired stock movements for atomic warehouse transfers.
 */
@Service
@ConditionalOnBean(StockMovementRepository.class)
@RequiredArgsConstructor
class TransferServiceImpl implements TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferServiceImpl.class);

    private final MaterialRepository materialRepository;
    private final WarehouseRepository warehouseRepository;
    private final StockMovementRepository stockMovementRepository;
    private final StockMovementMapper stockMovementMapper;
    private final InventoryValidationService inventoryValidationService;

    @Override
    @Transactional
    public List<StockMovement> transfer(StockMovementTransferRequest request) {
        inventoryValidationService.validateDifferentWarehouses(request.sourceWarehouseId(), request.targetWarehouseId());
        Material material = materialRepository.findById(request.materialId())
                .orElseThrow(() -> new NotFoundException("Material", request.materialId()));
        Warehouse sourceWarehouse = warehouseRepository.findById(request.sourceWarehouseId())
                .orElseThrow(() -> new NotFoundException("Source warehouse", request.sourceWarehouseId()));
        Warehouse targetWarehouse = warehouseRepository.findById(request.targetWarehouseId())
                .orElseThrow(() -> new NotFoundException("Target warehouse", request.targetWarehouseId()));
        inventoryValidationService.validateActive(material);
        inventoryValidationService.validateActive(sourceWarehouse);
        inventoryValidationService.validateActive(targetWarehouse);
        inventoryValidationService.validateAvailableStock(material.getId(), sourceWarehouse.getId(), request.quantity());

        StockMovement outgoingMovement = stockMovementMapper.toOutgoingTransferEntity(request);
        StockMovement incomingMovement = stockMovementMapper.toIncomingTransferEntity(request);
        Instant occurredAt = request.occurredAt() == null ? Instant.now() : request.occurredAt();
        outgoingMovement.setMaterial(material);
        outgoingMovement.setWarehouse(sourceWarehouse);
        outgoingMovement.setOccurredAt(occurredAt);
        incomingMovement.setMaterial(material);
        incomingMovement.setWarehouse(targetWarehouse);
        incomingMovement.setOccurredAt(occurredAt);

        StockMovement savedOutgoingMovement = stockMovementRepository.save(outgoingMovement);
        incomingMovement.relateTo(savedOutgoingMovement);
        StockMovement savedIncomingMovement = stockMovementRepository.save(incomingMovement);
        savedOutgoingMovement.relateTo(savedIncomingMovement);
        log.info("Transfer completed for material {} from warehouse {} to warehouse {}", material.getCode(), sourceWarehouse.getCode(), targetWarehouse.getCode());
        return List.of(savedOutgoingMovement, savedIncomingMovement);
    }
}