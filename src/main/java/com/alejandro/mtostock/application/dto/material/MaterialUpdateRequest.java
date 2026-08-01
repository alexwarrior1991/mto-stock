package com.alejandro.mtostock.application.dto.material;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Request DTO used to replace editable material catalogue data.
 */
public record MaterialUpdateRequest(
        @NotBlank
        @Size(max = 64)
        String code,

        @NotBlank
        @Size(max = 255)
        String name,

        @NotBlank
        @Size(max = 32)
        String unitOfMeasure,

        @NotNull
        @PositiveOrZero
        @Digits(integer = 13, fraction = 6)
        BigDecimal minimumStockLevel,

        @NotNull
        Boolean active
) {
}