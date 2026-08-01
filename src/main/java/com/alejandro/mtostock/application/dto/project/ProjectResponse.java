package com.alejandro.mtostock.application.dto.project;

import com.alejandro.mtostock.application.dto.common.AuditMetadataResponse;

import java.util.UUID;

/**
 * Response DTO exposing project data without exposing persistence entities.
 */
public record ProjectResponse(
        UUID id,
        String code,
        String name,
        Boolean active,
        AuditMetadataResponse audit
) {
}