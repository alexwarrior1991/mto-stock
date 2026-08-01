package com.alejandro.mtostock.application.dto;

import com.alejandro.mtostock.application.dto.assembly.AssemblyComponentRequest;
import com.alejandro.mtostock.application.dto.assembly.AssemblyRequest;
import com.alejandro.mtostock.application.dto.material.MaterialRequest;
import com.alejandro.mtostock.application.dto.reservation.ReservationStatusDto;
import com.alejandro.mtostock.application.dto.reservation.ReservationStatusUpdateRequest;
import com.alejandro.mtostock.application.dto.stock.StockMovementTransferRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DtoValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void materialRequestRejectsBlankCodeAndMissingMinimumStockLevel() {
        MaterialRequest request = new MaterialRequest(" ", "Contact wire", "m", null);

        var violations = validator.validate(request);

        assertTrue(violations.stream().anyMatch(violation -> "code".contentEquals(violation.getPropertyPath().toString())));
        assertTrue(violations.stream().anyMatch(violation -> "minimumStockLevel".contentEquals(violation.getPropertyPath().toString())));
    }

    @Test
    void assemblyRequestValidatesNestedBomComponents() {
        AssemblyRequest request = new AssemblyRequest(
                "ASM-001",
                "Basic catenary section",
                List.of(new AssemblyComponentRequest(UUID.randomUUID(), BigDecimal.ZERO))
        );

        var violations = validator.validate(request);

        assertTrue(violations.stream().anyMatch(violation -> violation.getPropertyPath().toString().contains("quantity")));
    }

    @Test
    void stockTransferRequestRejectsSameSourceAndTargetWarehouse() {
        UUID warehouseId = UUID.randomUUID();
        StockMovementTransferRequest request = new StockMovementTransferRequest(
                UUID.randomUUID(),
                warehouseId,
                warehouseId,
                new BigDecimal("1.000000"),
                null,
                null,
                null
        );

        var violations = validator.validate(request);

        assertTrue(violations.stream().anyMatch(violation -> "differentWarehouses".contentEquals(violation.getPropertyPath().toString())));
    }

    @Test
    void reservationStatusUpdateRequestRejectsNonTerminalStatus() {
        ReservationStatusUpdateRequest request = new ReservationStatusUpdateRequest(ReservationStatusDto.ACTIVE, null);

        var violations = validator.validate(request);

        assertTrue(violations.stream().anyMatch(violation -> "terminalStatus".contentEquals(violation.getPropertyPath().toString())));
    }

}