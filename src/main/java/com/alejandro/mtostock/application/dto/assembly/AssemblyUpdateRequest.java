package com.alejandro.mtostock.application.dto.assembly;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request DTO used to replace editable assembly data and its bill of materials.
 */
public record AssemblyUpdateRequest(
        @NotBlank
        @Size(max = 64)
        String code,

        @NotBlank
        @Size(max = 255)
        String name,

        @NotNull
        Boolean active,

        @NotEmpty
        List<@Valid AssemblyComponentRequest> components
) {
}