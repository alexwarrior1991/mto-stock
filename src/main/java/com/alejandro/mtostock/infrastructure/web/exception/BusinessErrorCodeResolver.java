package com.alejandro.mtostock.infrastructure.web.exception;

import com.alejandro.mtostock.application.exception.AssemblyException;
import com.alejandro.mtostock.application.exception.BusinessException;
import com.alejandro.mtostock.application.exception.DuplicateCodeException;
import com.alejandro.mtostock.application.exception.IdempotencyKeyConflictException;
import com.alejandro.mtostock.application.exception.InsufficientStockException;
import com.alejandro.mtostock.application.exception.NotFoundException;
import com.alejandro.mtostock.application.exception.ProjectException;
import com.alejandro.mtostock.application.exception.ReservationException;
import com.alejandro.mtostock.application.exception.StockMovementException;
import com.alejandro.mtostock.application.exception.ValidationException;
import com.alejandro.mtostock.application.exception.WarehouseException;

import java.util.Map;

/**
 * Resolves stable public error codes for business exceptions independently from mutable messages.
 */
final class BusinessErrorCodeResolver {

    private static final Map<String, String> AGGREGATE_PREFIXES = Map.ofEntries(
            Map.entry("Assembly", "ASM"),
            Map.entry("Material", "MAT"),
            Map.entry("Project", "PRJ"),
            Map.entry("Reservation", "RES"),
            Map.entry("StockMovement", "STK"),
            Map.entry("Stock movement", "STK"),
            Map.entry("Supplier", "SUP"),
            Map.entry("Warehouse", "WH"),
            Map.entry("Source warehouse", "WH"),
            Map.entry("Target warehouse", "WH")
    );

    /**
     * The unique constraint of each catalogue code and the aggregate it belongs to. A duplicate that
     * slips past the service's own check (two creates at once) is told with the same code as the one
     * the check finds.
     */
    private static final Map<String, String> CODE_CONSTRAINT_AGGREGATES = Map.of(
            "uq_material_code", "Material",
            "uq_supplier_code", "Supplier",
            "uq_warehouse_code", "Warehouse",
            "uq_project_code", "Project",
            "uq_assembly_code", "Assembly"
    );

    private BusinessErrorCodeResolver() {
    }

    /**
     * @param constraintName the name of the violated unique constraint, as the database reports it
     * @return the aggregate whose business code that constraint keeps unique, or {@code null} when
     *         it is the unique key of something else
     */
    static String aggregateOfCodeConstraint(String constraintName) {
        return constraintName == null ? null : CODE_CONSTRAINT_AGGREGATES.get(constraintName.toLowerCase(java.util.Locale.ROOT));
    }

    static String duplicateCode(String aggregate) {
        return aggregate == null ? "APP-409" : aggregateCode(aggregate, "409", "APP-409");
    }

    static String resolve(BusinessException exception) {
        return switch (exception) {
            case NotFoundException notFoundException -> aggregateCode(notFoundException.getAggregate(), "404", "APP-404");
            case DuplicateCodeException duplicateCodeException -> aggregateCode(duplicateCodeException.getAggregate(), "409", "APP-409");
            case InsufficientStockException ignored -> "STK-001";
            case IdempotencyKeyConflictException ignored -> "IDEM-001";
            case StockMovementException ignored -> "STK-002";
            case ReservationException ignored -> "RES-001";
            case AssemblyException ignored -> "ASM-001";
            case WarehouseException ignored -> "WH-001";
            case ProjectException ignored -> "PRJ-001";
            case ValidationException ignored -> "VAL-001";
            default -> "BUS-001";
        };
    }

    private static String aggregateCode(String aggregate, String suffix, String fallback) {
        String prefix = AGGREGATE_PREFIXES.get(aggregate);
        return prefix == null ? fallback : "%s-%s".formatted(prefix, suffix);
    }
}