package com.alejandro.mtostock.application.dto;

import com.alejandro.mtostock.application.dto.assembly.AssemblyComponentRequest;
import com.alejandro.mtostock.application.dto.assembly.AssemblyRequest;
import com.alejandro.mtostock.application.dto.assembly.AssemblyUpdateRequest;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    /**
     * Un material repetido en la lista es un error del campo {@code components}, una sola vez, en el
     * alta y en la modificación; antes llegaba a la restricción única de la base de datos (500). La
     * misma lista sin repetir pasa.
     */
    @Test
    void assemblyRequestsRefuseTheSameMaterialTwiceOnComponents() {
        UUID materialId = UUID.randomUUID();
        List<AssemblyComponentRequest> repeated = List.of(
                new AssemblyComponentRequest(materialId, new BigDecimal("2.000000")),
                new AssemblyComponentRequest(UUID.randomUUID(), new BigDecimal("1.000000")),
                new AssemblyComponentRequest(materialId, new BigDecimal("3.000000")));
        List<AssemblyComponentRequest> distinct = repeated.subList(0, 2);

        var onCreate = validator.validate(new AssemblyRequest("ASM-001", "Basic catenary section", repeated));
        var onUpdate = validator.validate(new AssemblyUpdateRequest("ASM-001", "Basic catenary section", true, repeated));

        assertEquals(List.of("components"), onCreate.stream().map(violation -> violation.getPropertyPath().toString()).toList());
        assertEquals(List.of("components"), onUpdate.stream().map(violation -> violation.getPropertyPath().toString()).toList());
        assertTrue(validator.validate(new AssemblyRequest("ASM-001", "Basic catenary section", distinct)).isEmpty());
        assertTrue(validator.validate(new AssemblyUpdateRequest("ASM-001", "Basic catenary section", true, distinct)).isEmpty());
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