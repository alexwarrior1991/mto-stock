package com.alejandro.mtostock.application.service.impl;

import com.alejandro.mtostock.application.dto.audit.EntityRevisionResponse;
import com.alejandro.mtostock.application.dto.common.PageResponse;
import com.alejandro.mtostock.application.dto.reservation.ReservationRequest;
import com.alejandro.mtostock.application.dto.reservation.ReservationResponse;
import com.alejandro.mtostock.application.dto.reservation.ReservationUpdateRequest;
import com.alejandro.mtostock.application.exception.NotFoundException;
import com.alejandro.mtostock.application.mapper.ReservationMapper;
import com.alejandro.mtostock.application.service.EntityAuditService;
import com.alejandro.mtostock.application.service.ReservationEngine;
import com.alejandro.mtostock.application.service.ReservationService;
import com.alejandro.mtostock.infrastructure.persistence.entity.EntityReferenceFactory;
import com.alejandro.mtostock.infrastructure.persistence.entity.Reservation;
import com.alejandro.mtostock.infrastructure.persistence.entity.ReservationStatus;
import com.alejandro.mtostock.infrastructure.persistence.repository.ReservationRepository;
import com.alejandro.mtostock.infrastructure.persistence.specification.ReservationSpecification;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Orchestrates reservation use cases through the dedicated reservation engine.
 */
@Service
@RequiredArgsConstructor
class ReservationServiceImpl implements ReservationService {

    private final ReservationRepository reservationRepository;
    private final ReservationMapper reservationMapper;
    private final EntityAuditService entityAuditService;
    private final ReservationEngine reservationEngine;
    private final EntityReferenceFactory entityReferenceFactory;

    @Override
    @Transactional
    public ReservationResponse create(ReservationRequest request) {
        Reservation reservation = reservationMapper.toEntity(request);
        reservation.setReservedAt(request.reservedAt() == null ? Instant.now() : request.reservedAt());
        return reservationMapper.toResponse(reservationEngine.create(reservation));
    }

    @Override
    @Transactional
    public ReservationResponse update(UUID id, ReservationUpdateRequest request) {
        Reservation currentReservation = reservationRepository.findById(id).orElseThrow(() -> new NotFoundException("Reservation", id));
        Reservation requestedReservation = Reservation.builder()
                .material(currentReservation.getMaterial())
                .warehouse(entityReferenceFactory.toWarehouse(request.warehouseId()))
                .project(entityReferenceFactory.toProject(request.projectId()))
                .quantity(request.quantity())
                .build();
        return reservationMapper.toResponse(reservationEngine.update(id, requestedReservation));
    }

    @Override
    @Transactional
    public ReservationResponse cancel(UUID id) {
        return reservationMapper.toResponse(reservationEngine.cancel(id));
    }

    @Override
    @Transactional
    public ReservationResponse release(UUID id) {
        return reservationMapper.toResponse(reservationEngine.release(id));
    }

    @Override
    @Transactional
    public ReservationResponse consume(UUID id) {
        return reservationMapper.toResponse(reservationEngine.consume(id));
    }

    @Override
    @Transactional(readOnly = true)
    public ReservationResponse findById(UUID id) {
        return reservationMapper.toResponse(reservationRepository.findById(id).orElseThrow(() -> new NotFoundException("Reservation", id)));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ReservationResponse> search(UUID warehouseId, ReservationStatus status, UUID projectId, UUID materialId, Pageable pageable) {
        Specification<Reservation> specification = Specification.where(ReservationSpecification.warehouseIdEquals(warehouseId))
                .and(ReservationSpecification.statusEquals(status))
                .and(ReservationSpecification.projectIdEquals(projectId))
                .and(ReservationSpecification.materialIdEquals(materialId));
        return reservationMapper.toPageResponse(reservationRepository.findAll(specification, pageable));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<EntityRevisionResponse<ReservationResponse>> findRevisions(UUID id, Pageable pageable) {
        return entityAuditService.findRevisions(
                Reservation.class,
                id,
                reservationMapper::toResponse,
                pageable);
    }
}
