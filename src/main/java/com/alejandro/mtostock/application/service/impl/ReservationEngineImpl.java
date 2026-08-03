package com.alejandro.mtostock.application.service.impl;

import com.alejandro.mtostock.application.exception.NotFoundException;
import com.alejandro.mtostock.application.service.InventoryValidationService;
import com.alejandro.mtostock.application.service.ReservationEngine;
import com.alejandro.mtostock.infrastructure.persistence.entity.Material;
import com.alejandro.mtostock.infrastructure.persistence.entity.Project;
import com.alejandro.mtostock.infrastructure.persistence.entity.Reservation;
import com.alejandro.mtostock.infrastructure.persistence.entity.ReservationStatus;
import com.alejandro.mtostock.infrastructure.persistence.entity.Warehouse;
import com.alejandro.mtostock.infrastructure.persistence.repository.MaterialRepository;
import com.alejandro.mtostock.infrastructure.persistence.repository.ProjectRepository;
import com.alejandro.mtostock.infrastructure.persistence.repository.ReservationRepository;
import com.alejandro.mtostock.infrastructure.persistence.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Applies reservation lifecycle rules while preserving movement-derived stock semantics.
 */
@Service
@ConditionalOnBean(ReservationRepository.class)
@RequiredArgsConstructor
class ReservationEngineImpl implements ReservationEngine {

    private static final Logger log = LoggerFactory.getLogger(ReservationEngineImpl.class);

    private final ReservationRepository reservationRepository;
    private final MaterialRepository materialRepository;
    private final WarehouseRepository warehouseRepository;
    private final ProjectRepository projectRepository;
    private final InventoryValidationService inventoryValidationService;

    @Override
    @Transactional
    public Reservation create(Reservation reservation) {
        attachManagedReferences(reservation);
        reservation.setStatus(ReservationStatus.ACTIVE);
        inventoryValidationService.validatePositiveQuantity(reservation.getQuantity());
        inventoryValidationService.validateActive(reservation.getMaterial());
        inventoryValidationService.validateActive(reservation.getWarehouse());
        inventoryValidationService.validateAvailableStock(
                reservation.getMaterial().getId(),
                reservation.getWarehouse().getId(),
                reservation.getQuantity()
        );
        Reservation savedReservation = reservationRepository.save(reservation);
        log.info("Reservation created for material {} in warehouse {}", reservation.getMaterial().getCode(), reservation.getWarehouse().getCode());
        return savedReservation;
    }

    @Override
    @Transactional
    public Reservation update(UUID id, Reservation reservation) {
        Reservation existingReservation = findReservation(id);
        inventoryValidationService.validateReservationCanChange(existingReservation);
        attachManagedReferences(reservation);
        inventoryValidationService.validatePositiveQuantity(reservation.getQuantity());
        inventoryValidationService.validateActive(reservation.getMaterial());
        inventoryValidationService.validateActive(reservation.getWarehouse());
        inventoryValidationService.validateAvailableStock(
                reservation.getMaterial().getId(),
                reservation.getWarehouse().getId(),
                reservation.getQuantity(),
                reservation.getWarehouse().getId().equals(existingReservation.getWarehouse().getId())
                        ? existingReservation.getQuantity()
                        : java.math.BigDecimal.ZERO
        );
        existingReservation.setWarehouse(reservation.getWarehouse());
        existingReservation.setProject(reservation.getProject());
        existingReservation.setQuantity(reservation.getQuantity());
        log.info("Reservation {} updated", id);
        return existingReservation;
    }

    @Override
    @Transactional
    public Reservation cancel(UUID id) {
        Reservation reservation = findReservation(id);
        inventoryValidationService.validateReservationCanChange(reservation);
        reservation.cancel(Instant.now());
        log.info("Reservation {} cancelled", id);
        return reservation;
    }

    @Override
    @Transactional
    public Reservation release(UUID id) {
        Reservation reservation = findReservation(id);
        inventoryValidationService.validateReservationCanChange(reservation);
        reservation.release(Instant.now());
        log.info("Reservation {} released", id);
        return reservation;
    }

    @Override
    @Transactional
    public Reservation consume(UUID id) {
        Reservation reservation = findReservation(id);
        inventoryValidationService.validateReservationCanChange(reservation);
        reservation.release(Instant.now());
        log.info("Reservation {} consumed", id);
        return reservation;
    }

    private Reservation findReservation(UUID id) {
        return reservationRepository.findById(id).orElseThrow(() -> new NotFoundException("Reservation", id));
    }

    private void attachManagedReferences(Reservation reservation) {
        Material material = materialRepository.findById(reservation.getMaterial().getId())
                .orElseThrow(() -> new NotFoundException("Material", reservation.getMaterial().getId()));
        Warehouse warehouse = warehouseRepository.findById(reservation.getWarehouse().getId())
                .orElseThrow(() -> new NotFoundException("Warehouse", reservation.getWarehouse().getId()));
        Project project = projectRepository.findById(reservation.getProject().getId())
                .orElseThrow(() -> new NotFoundException("Project", reservation.getProject().getId()));
        reservation.setMaterial(material);
        reservation.setWarehouse(warehouse);
        reservation.setProject(project);
    }
}