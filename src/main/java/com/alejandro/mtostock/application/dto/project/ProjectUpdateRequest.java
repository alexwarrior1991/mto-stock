package com.alejandro.mtostock.application.dto.project;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request DTO used to replace editable project data.
 */
public record ProjectUpdateRequest(
        @NotBlank
        @Size(max = 64)
        String code,

        @NotBlank
        @Size(max = 255)
        String name,

        @NotNull
        Boolean active
) {
}