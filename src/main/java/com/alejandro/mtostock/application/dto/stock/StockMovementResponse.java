package com.alejandro.mtostock.application.dto.stock;

import com.alejandro.mtostock.application.dto.common.AuditMetadataResponse;
import com.alejandro.mtostock.application.dto.material.MaterialSummaryResponse;
import com.alejandro.mtostock.application.dto.project.ProjectSummaryResponse;
import com.alejandro.mtostock.application.dto.reservation.ReservationSummaryResponse;
import com.alejandro.mtostock.application.dto.supplier.SupplierSummaryResponse;
import com.alejandro.mtostock.application.dto.warehouse.WarehouseSummaryResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO exposing a stock ledger movement without exposing persistence entities.
 */
public record StockMovementResponse(
        UUID id,
        MaterialSummaryResponse material,
        WarehouseSummaryResponse warehouse,
        StockMovementTypeDto type,
        BigDecimal quantity,
        BigDecimal signedQuantity,
        Instant occurredAt,
        SupplierSummaryResponse supplier,
        ProjectSummaryResponse project,
        ReservationSummaryResponse reservation,
        StockMovementSummaryResponse relatedMovement,
        String externalReference,
        String notes,
        AuditMetadataResponse audit
) {
}