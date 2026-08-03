package com.alejandro.mtostock.application.dto.error;

/**
 * Describes one field, parameter or object validation failure returned to API clients.
 */
public record ValidationError(
        String field,
        String message
) {
}