package com.alejandro.mtostock.infrastructure.web.exception;

import com.alejandro.mtostock.application.exception.AssemblyException;
import com.alejandro.mtostock.application.exception.BusinessException;
import com.alejandro.mtostock.application.exception.DuplicateCodeException;
import com.alejandro.mtostock.application.exception.InsufficientStockException;
import com.alejandro.mtostock.application.exception.NotFoundException;
import com.alejandro.mtostock.application.exception.ReservationException;
import com.alejandro.mtostock.application.exception.StockMovementException;
import com.alejandro.mtostock.application.exception.ValidationException;
import com.alejandro.mtostock.application.exception.WarehouseException;

import java.util.Map;

/**
 * Resolves stable public error codes for business exceptions independently from mutable messages.
 */
final class BusinessErrorCodeResolver {

    private static final Map<String, String> AGGREGATE_PREFIXES = Map.of(
            "Assembly", "ASM",
            "Material", "MAT",
            "Project", "PRJ",
            "Reservation", "RES",
            "StockMovement", "STK",
            "Supplier", "SUP",
            "Warehouse", "WH"
    );

    private BusinessErrorCodeResolver() {
    }

    static String resolve(BusinessException exception) {
        return switch (exception) {
            case NotFoundException notFoundException -> aggregateCode(notFoundException.getAggregate(), "404", "APP-404");
            case DuplicateCodeException duplicateCodeException -> aggregateCode(duplicateCodeException.getAggregate(), "409", "APP-409");
            case InsufficientStockException ignored -> "STK-001";
            case StockMovementException ignored -> "STK-002";
            case ReservationException ignored -> "RES-001";
            case AssemblyException ignored -> "ASM-001";
            case WarehouseException ignored -> "WH-001";
            case ValidationException ignored -> "VAL-001";
            default -> "BUS-001";
        };
    }

    private static String aggregateCode(String aggregate, String suffix, String fallback) {
        String prefix = AGGREGATE_PREFIXES.get(aggregate);
        return prefix == null ? fallback : "%s-%s".formatted(prefix, suffix);
    }
}