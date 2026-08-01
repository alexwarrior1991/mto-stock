package com.alejandro.mtostock.domain.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DomainModelTest {

    @Test
    void stockIsDerivedFromMovementHistory() {
        Material material = material("CAT-001", "Contact wire", "m");
        Warehouse warehouse = warehouse("WH-001");

        Quantity currentStock = StockLevel.currentStock(List.of(
                StockMovement.entry(material, warehouse, Quantity.of("100")),
                StockMovement.output(material, warehouse, Quantity.of("12.5")),
                StockMovement.positiveAdjustment(material, warehouse, Quantity.of("2.5")),
                StockMovement.negativeAdjustment(material, warehouse, Quantity.of("10")),
                StockMovement.incomingTransfer(material, warehouse, Quantity.of("20")),
                StockMovement.outgoingTransfer(material, warehouse, Quantity.of("5"))
        ));

        assertQuantity("95", currentStock);
    }

    @Test
    void availableStockSubtractsOnlyActiveReservations() {
        Material material = material("CAT-002", "Dropper", "pcs");
        Warehouse warehouse = warehouse("WH-001");
        Project project = project("PRJ-001");

        Quantity availableStock = StockLevel.availableStock(
                List.of(StockMovement.entry(material, warehouse, Quantity.of("10"))),
                List.of(
                        new Reservation(material, warehouse, project, Quantity.of("3"), ReservationStatus.ACTIVE),
                        new Reservation(material, warehouse, project, Quantity.of("4"), ReservationStatus.CANCELLED)
                )
        );

        assertQuantity("7", availableStock);
    }

    @Test
    void assemblyAvailabilityIsCalculatedFromBomAndComponentStock() {
        Material contactWire = material("CAT-003", "Contact wire", "m");
        Material insulator = material("CAT-004", "Insulator", "pcs");
        Assembly assembly = new Assembly("ASM-001", "Basic catenary section", List.of(
                new AssemblyComponent(contactWire, Quantity.of("10")),
                new AssemblyComponent(insulator, Quantity.of("2"))
        ), true);

        long availableAssemblies = assembly.availableUnits(Map.of(
                contactWire, Quantity.of("36"),
                insulator, Quantity.of("7")
        ));

        assertEquals(3, availableAssemblies);
    }

    @Test
    void invalidDomainValuesAreRejected() {
        Material material = material("CAT-005", "Bracket", "pcs");
        Warehouse warehouse = warehouse("WH-001");

        assertThrows(IllegalArgumentException.class, () -> Quantity.of("-1"));
        assertThrows(IllegalArgumentException.class, () -> StockMovement.output(material, warehouse, Quantity.zero()));
        assertThrows(IllegalArgumentException.class, () -> new Assembly("ASM-002", "Invalid assembly", List.of(), true));
    }

    private static Material material(String code, String name, String unitOfMeasure) {
        return new Material(code, name, unitOfMeasure, Quantity.zero(), true);
    }

    private static Warehouse warehouse(String code) {
        return new Warehouse(code, "Main warehouse", true);
    }

    private static Project project(String code) {
        return new Project(code, "Railway project", true);
    }

    private static void assertQuantity(String expected, Quantity actual) {
        assertTrue(Quantity.of(expected).hasSameValueAs(actual));
    }

}