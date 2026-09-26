package com.alejandro.mtostock.application.service;

import com.alejandro.mtostock.application.dto.common.PageResponse;
import com.alejandro.mtostock.application.dto.stock.StockMovementAdjustmentRequest;
import com.alejandro.mtostock.application.dto.stock.StockMovementEntryRequest;
import com.alejandro.mtostock.application.dto.stock.StockMovementOutputRequest;
import com.alejandro.mtostock.application.dto.stock.StockMovementResponse;
import com.alejandro.mtostock.infrastructure.persistence.entity.StockMovementType;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.UUID;

/**
 * Application service exposing append-only stock ledger use cases.
 */
public interface StockMovementService {

    StockMovementResponse registerEntry(StockMovementEntryRequest request);

    /**
     * Registra la salida. Con {@code idempotencyKey}, repetir la petición con el mismo cuerpo no
     * saca el material otra vez: devuelve el movimiento que creó la primera.
     *
     * @param idempotencyKey la cabecera {@code Idempotency-Key}, o {@code null}
     * @see IdempotentRequestService
     */
    StockMovementResponse registerOutput(StockMovementOutputRequest request, String idempotencyKey);

    StockMovementResponse registerAdjustment(StockMovementAdjustmentRequest request);

    StockMovementResponse findById(UUID id);

    PageResponse<StockMovementResponse> search(StockMovementType type, UUID warehouseId, UUID projectId, UUID materialId,
                                               Instant from, Instant to, String user, Pageable pageable);
}