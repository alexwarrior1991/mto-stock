package com.alejandro.mtostock.application.dto.project;

import com.alejandro.mtostock.application.dto.common.AuditMetadataResponse;

import java.util.UUID;

/**
 * Response DTO exposing project data without exposing persistence entities.
 *
 * <p>{@code sourceService} names the service a project was synchronized from ({@code mto-configuration}
 * for an execution package; {@code null} when it was created through this API) and
 * {@code synchronizedFromMasterData} says the same as a flag: such a project is owned by its source,
 * the next event overwrites it, and {@code PUT} refuses it with {@code PRJ-001}.
 */
public record ProjectResponse(
        UUID id,
        String code,
        String name,
        Boolean active,
        String sourceService,
        boolean synchronizedFromMasterData,
        AuditMetadataResponse audit
) {
}