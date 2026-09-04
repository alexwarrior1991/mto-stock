package com.alejandro.mtostock.application.service.impl;

import com.alejandro.mtostock.application.dto.common.PageResponse;
import com.alejandro.mtostock.application.dto.stock.StockAdjustmentDirection;
import com.alejandro.mtostock.application.dto.stock.StockMovementAdjustmentRequest;
import com.alejandro.mtostock.application.dto.stock.StockMovementEntryRequest;
import com.alejandro.mtostock.application.dto.stock.StockMovementOutputRequest;
import com.alejandro.mtostock.application.dto.stock.StockMovementResponse;
import com.alejandro.mtostock.application.exception.NotFoundException;
import com.alejandro.mtostock.application.exception.ReservationException;
import com.alejandro.mtostock.application.mapper.StockMovementMapper;
import com.alejandro.mtostock.application.service.InventoryBalanceService;
import com.alejandro.mtostock.application.service.InventoryValidationService;
import com.alejandro.mtostock.application.service.ReservationEngine;
import com.alejandro.mtostock.application.service.StockMovementService;
import com.alejandro.mtostock.infrastructure.persistence.entity.Material;
import com.alejandro.mtostock.infrastructure.persistence.entity.Project;
import com.alejandro.mtostock.infrastructure.persistence.entity.Reservation;
import com.alejandro.mtostock.infrastructure.persistence.entity.StockMovement;
import com.alejandro.mtostock.infrastructure.persistence.entity.StockMovementType;
import com.alejandro.mtostock.infrastructure.persistence.entity.Supplier;
import com.alejandro.mtostock.infrastructure.persistence.entity.Warehouse;
import com.alejandro.mtostock.infrastructure.persistence.repository.MaterialRepository;
import com.alejandro.mtostock.infrastructure.persistence.repository.ProjectRepository;
import com.alejandro.mtostock.infrastructure.persistence.repository.ReservationRepository;
import com.alejandro.mtostock.infrastructure.persistence.repository.StockMovementRepository;
import com.alejandro.mtostock.infrastructure.persistence.repository.SupplierRepository;
import com.alejandro.mtostock.infrastructure.persistence.repository.WarehouseRepository;
import com.alejandro.mtostock.infrastructure.persistence.specification.StockMovementSpecification;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Orchestrates append-only stock movement use cases and delegates stock rules to inventory engines.
 */
@Service
@RequiredArgsConstructor
class StockMovementServiceImpl implements StockMovementService {

    private static final Logger log = LoggerFactory.getLogger(StockMovementServiceImpl.class);

    private final StockMovementRepository stockMovementRepository;
    private final MaterialRepository materialRepository;
    private final WarehouseRepository warehouseRepository;
    private final SupplierRepository supplierRepository;
    private final ProjectRepository projectRepository;
    private final ReservationRepository reservationRepository;
    private final StockMovementMapper stockMovementMapper;
    private final InventoryBalanceService inventoryBalanceService;
    private final InventoryValidationService inventoryValidationService;
    private final ReservationEngine reservationEngine;

    @Override
    @Transactional
    public StockMovementResponse registerEntry(StockMovementEntryRequest request) {
        StockMovement movement = stockMovementMapper.toEntryEntity(request);
        attachMaterialAndWarehouse(movement, request.materialId(), request.warehouseId());
        if (request.supplierId() != null) {
            Supplier supplier = supplierRepository.findById(request.supplierId())
                    .orElseThrow(() -> new NotFoundException("Supplier", request.supplierId()));
            movement.setSupplier(supplier);
        }
        normalizeOccurredAt(movement);
        StockMovement savedMovement = stockMovementRepository.save(movement);
        inventoryBalanceService.increasePhysical(request.materialId(), request.warehouseId(), request.quantity());
        log.info("Stock entry registered for material {} in warehouse {}", movement.getMaterial().getCode(), movement.getWarehouse().getCode());
        return stockMovementMapper.toResponse(savedMovement);
    }

    @Override
    @Transactional
    public StockMovementResponse registerOutput(StockMovementOutputRequest request) {
        StockMovement movement = stockMovementMapper.toOutputEntity(request);
        attachMaterialAndWarehouse(movement, request.materialId(), request.warehouseId());
        if (request.projectId() != null) {
            Project project = projectRepository.findById(request.projectId())
                    .orElseThrow(() -> new NotFoundException("Project", request.projectId()));
            movement.setProject(project);
        }
        Reservation reservation = request.reservationId() == null ? null : reservationRepository.findById(request.reservationId())
                .orElseThrow(() -> new NotFoundException("Reservation", request.reservationId()));
        validateOutputStock(request, reservation);
        if (reservation == null) {
            inventoryBalanceService.decreasePhysicalAndAvailable(request.materialId(), request.warehouseId(), request.quantity());
        }
        movement.setReservation(reservation);
        normalizeOccurredAt(movement);
        StockMovement savedMovement = stockMovementRepository.save(movement);
        if (reservation != null) {
            reservationEngine.consume(reservation.getId());
        }
        log.info("Stock output registered for material {} in warehouse {}", movement.getMaterial().getCode(), movement.getWarehouse().getCode());
        return stockMovementMapper.toResponse(savedMovement);
    }

    @Override
    @Transactional
    public StockMovementResponse registerAdjustment(StockMovementAdjustmentRequest request) {
        StockMovement movement = stockMovementMapper.toAdjustmentEntity(request);
        attachMaterialAndWarehouse(movement, request.materialId(), request.warehouseId());
        if (request.direction() == StockAdjustmentDirection.NEGATIVE) {
            inventoryBalanceService.decreasePhysicalAndAvailable(request.materialId(), request.warehouseId(), request.quantity());
        } else {
            inventoryValidationService.validatePositiveQuantity(request.quantity());
        }
        normalizeOccurredAt(movement);
        StockMovement savedMovement = stockMovementRepository.save(movement);
        if (request.direction() == StockAdjustmentDirection.POSITIVE) {
            inventoryBalanceService.increasePhysical(request.materialId(), request.warehouseId(), request.quantity());
        }
        log.info("Stock adjustment registered for material {} in warehouse {}", movement.getMaterial().getCode(), movement.getWarehouse().getCode());
        return stockMovementMapper.toResponse(savedMovement);
    }

    @Override
    @Transactional(readOnly = true)
    public StockMovementResponse findById(UUID id) {
        return stockMovementMapper.toResponse(stockMovementRepository.findById(id).orElseThrow(() -> new NotFoundException("Stock movement", id)));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<StockMovementResponse> search(StockMovementType type, UUID warehouseId, UUID projectId, UUID materialId,
                                                      Instant from, Instant to, String user, Pageable pageable) {
        Specification<StockMovement> specification = Specification.where(StockMovementSpecification.typeEquals(type))
                .and(StockMovementSpecification.warehouseIdEquals(warehouseId))
                .and(StockMovementSpecification.projectIdEquals(projectId))
                .and(StockMovementSpecification.materialIdEquals(materialId))
                .and(StockMovementSpecification.occurredAtFrom(from))
                .and(StockMovementSpecification.occurredAtTo(to))
                .and(StockMovementSpecification.createdByContains(user));
        return stockMovementMapper.toPageResponse(stockMovementRepository.findAll(specification, pageable));
    }

    private void attachMaterialAndWarehouse(StockMovement movement, UUID materialId, UUID warehouseId) {
        Material material = materialRepository.findById(materialId).orElseThrow(() -> new NotFoundException("Material", materialId));
        Warehouse warehouse = warehouseRepository.findById(warehouseId).orElseThrow(() -> new NotFoundException("Warehouse", warehouseId));
        inventoryValidationService.validateActive(material);
        inventoryValidationService.validateActive(warehouse);
        movement.setMaterial(material);
        movement.setWarehouse(warehouse);
    }

    private void validateOutputStock(StockMovementOutputRequest request, Reservation reservation) {
        if (reservation == null) {
            inventoryValidationService.validatePositiveQuantity(request.quantity());
            return;
        }
        inventoryValidationService.validateReservationCanChange(reservation);
        if (!reservation.getMaterial().getId().equals(request.materialId()) || !reservation.getWarehouse().getId().equals(request.warehouseId())) {
            throw new ReservationException("Reservation does not belong to the requested material and warehouse");
        }
        if (reservation.getQuantity().compareTo(request.quantity()) != 0) {
            throw new ReservationException("Reservation consumption must match the reserved quantity");
        }
    }

    private void normalizeOccurredAt(StockMovement movement) {
        if (movement.getOccurredAt() == null) {
            movement.setOccurredAt(Instant.now());
        }
    }
}