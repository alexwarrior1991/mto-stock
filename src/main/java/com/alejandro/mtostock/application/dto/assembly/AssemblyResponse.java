package com.alejandro.mtostock.application.dto.assembly;

import com.alejandro.mtostock.application.dto.common.AuditMetadataResponse;

import java.util.List;
import java.util.UUID;

/**
 * Response DTO exposing an assembly and its bill of materials without exposing persistence entities.
 */
public record AssemblyResponse(
        UUID id,
        String code,
        String name,
        Boolean active,
        List<AssemblyComponentResponse> components,
        AuditMetadataResponse audit
) {
}