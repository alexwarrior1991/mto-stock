package com.alejandro.mtostock.application.dto.assembly;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Request DTO representing a single bill of materials component line.
 */
public record AssemblyComponentRequest(
        @NotNull
        UUID materialId,

        @NotNull
        @Positive
        @Digits(integer = 13, fraction = 6)
        BigDecimal quantity
) {
}