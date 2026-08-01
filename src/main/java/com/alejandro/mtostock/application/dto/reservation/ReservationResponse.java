package com.alejandro.mtostock.application.dto.reservation;

import com.alejandro.mtostock.application.dto.common.AuditMetadataResponse;
import com.alejandro.mtostock.application.dto.material.MaterialSummaryResponse;
import com.alejandro.mtostock.application.dto.project.ProjectSummaryResponse;
import com.alejandro.mtostock.application.dto.warehouse.WarehouseSummaryResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO exposing a reservation without exposing persistence entities.
 */
public record ReservationResponse(
        UUID id,
        MaterialSummaryResponse material,
        WarehouseSummaryResponse warehouse,
        ProjectSummaryResponse project,
        BigDecimal quantity,
        ReservationStatusDto status,
        Instant reservedAt,
        Instant releasedAt,
        Boolean active,
        AuditMetadataResponse audit
) {
}