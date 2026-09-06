package com.alejandro.mtostock.application.service;

import com.alejandro.mtostock.application.dto.audit.EntityRevisionResponse;
import com.alejandro.mtostock.application.dto.common.PageResponse;
import com.alejandro.mtostock.application.dto.reservation.ReservationRequest;
import com.alejandro.mtostock.application.dto.reservation.ReservationResponse;
import com.alejandro.mtostock.application.dto.reservation.ReservationUpdateRequest;
import com.alejandro.mtostock.infrastructure.persistence.entity.ReservationStatus;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * Application service exposing reservation lifecycle use cases.
 */
public interface ReservationService {

    ReservationResponse create(ReservationRequest request);

    ReservationResponse update(UUID id, ReservationUpdateRequest request);

    ReservationResponse cancel(UUID id);

    ReservationResponse release(UUID id);

    ReservationResponse consume(UUID id);

    ReservationResponse findById(UUID id);

    PageResponse<ReservationResponse> search(UUID warehouseId, ReservationStatus status, UUID projectId, UUID materialId, Pageable pageable);

    /**
     * Historial de cambios de la reserva: cada transición de estado y cada ajuste de cantidad.
     *
     * @throws com.alejandro.mtostock.application.exception.NotFoundException si no existe
     */
    PageResponse<EntityRevisionResponse<ReservationResponse>> findRevisions(UUID id, Pageable pageable);
}
