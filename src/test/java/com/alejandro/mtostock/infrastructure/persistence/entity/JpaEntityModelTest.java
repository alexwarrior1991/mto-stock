package com.alejandro.mtostock.infrastructure.persistence.entity;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JpaEntityModelTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void materialValidationRejectsBlankCodeAndNegativeMinimumStock() {
        Material material = Material.builder()
                .code(" ")
                .name("Contact wire")
                .unitOfMeasure("m")
                .minimumStockLevel(new BigDecimal("-1.000000"))
                .build();

        var violations = validator.validate(material);

        assertTrue(violations.stream().anyMatch(violation -> "code".contentEquals(violation.getPropertyPath().toString())));
        assertTrue(violations.stream().anyMatch(violation -> "minimumStockLevel".contentEquals(violation.getPropertyPath().toString())));
    }

    @Test
    void assemblyMaintainsBidirectionalBomRelationship() {
        Assembly assembly = Assembly.builder()
                .code("ASM-001")
                .name("Basic catenary section")
                .build();
        AssemblyComponent component = AssemblyComponent.builder()
                .material(material())
                .quantity(new BigDecimal("2.000000"))
                .build();

        assembly.addComponent(component);

        assertEquals(1, assembly.getComponents().size());
        assertEquals(assembly, component.getAssembly());

        assembly.removeComponent(component);

        assertTrue(assembly.getComponents().isEmpty());
        assertNull(component.getAssembly());
    }

    @Test
    void reservationReleaseAndCancellationRequireActiveReservation() {
        Reservation reservation = reservation();
        Instant releasedAt = Instant.parse("2026-08-01T10:08:00Z");

        reservation.release(releasedAt);

        assertEquals(ReservationStatus.RELEASED, reservation.getStatus());
        assertEquals(releasedAt, reservation.getReleasedAt());
        assertFalse(reservation.isActive());
        assertThrows(IllegalStateException.class, () -> reservation.cancel(releasedAt));
    }

    @Test
    void stockMovementCalculatesSignedQuantityAndRejectsSelfRelation() {
        StockMovement entry = stockMovement(StockMovementType.ENTRY, "10.500000");
        StockMovement output = stockMovement(StockMovementType.OUTPUT, "4.250000");

        assertEquals(new BigDecimal("10.500000"), entry.signedQuantity());
        assertEquals(new BigDecimal("-4.250000"), output.signedQuantity());
        assertThrows(IllegalArgumentException.class, () -> entry.relateTo(entry));
    }

    private static StockMovement stockMovement(StockMovementType type, String quantity) {
        return StockMovement.builder()
                .material(material())
                .warehouse(warehouse())
                .type(type)
                .quantity(new BigDecimal(quantity))
                .build();
    }

    private static Reservation reservation() {
        return Reservation.builder()
                .material(material())
                .warehouse(warehouse())
                .project(project())
                .quantity(new BigDecimal("5.000000"))
                .build();
    }

    private static Material material() {
        return Material.builder()
                .code("MAT-001")
                .name("Contact wire")
                .unitOfMeasure("m")
                .build();
    }

    private static Warehouse warehouse() {
        return Warehouse.builder()
                .code("WH-001")
                .name("Main warehouse")
                .build();
    }

    private static Project project() {
        return Project.builder()
                .code("PRJ-001")
                .name("Railway project")
                .build();
    }

}