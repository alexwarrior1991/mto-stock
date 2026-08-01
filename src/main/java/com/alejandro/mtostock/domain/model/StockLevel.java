package com.alejandro.mtostock.domain.model;

import java.math.BigDecimal;
import java.util.Collection;

public final class StockLevel {

    private StockLevel() {
    }

    public static Quantity currentStock(Collection<StockMovement> movements) {
        BigDecimal currentStock = DomainValidations.requireNonNull(movements, "stock movements").stream()
                .map(StockMovement::signedQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return Quantity.of(currentStock);
    }

    public static Quantity currentStock(Collection<StockMovement> movements, Material material, Warehouse warehouse) {
        DomainValidations.requireNonNull(material, "material");
        DomainValidations.requireNonNull(warehouse, "warehouse");
        return currentStock(DomainValidations.requireNonNull(movements, "stock movements").stream()
                .filter(movement -> movement.material().equals(material))
                .filter(movement -> movement.warehouse().equals(warehouse))
                .toList());
    }

    public static Quantity availableStock(Collection<StockMovement> movements, Collection<Reservation> reservations) {
        BigDecimal availableStock = currentStock(movements).amount().subtract(activeReservedQuantity(reservations));
        return Quantity.of(availableStock);
    }

    public static Quantity availableStock(
            Collection<StockMovement> movements,
            Collection<Reservation> reservations,
            Material material,
            Warehouse warehouse
    ) {
        DomainValidations.requireNonNull(material, "material");
        DomainValidations.requireNonNull(warehouse, "warehouse");
        BigDecimal reservedQuantity = DomainValidations.requireNonNull(reservations, "reservations").stream()
                .filter(Reservation::isActive)
                .filter(reservation -> reservation.material().equals(material))
                .filter(reservation -> reservation.warehouse().equals(warehouse))
                .map(reservation -> reservation.quantity().amount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal availableStock = currentStock(movements, material, warehouse).amount().subtract(reservedQuantity);
        return Quantity.of(availableStock);
    }

    private static BigDecimal activeReservedQuantity(Collection<Reservation> reservations) {
        return DomainValidations.requireNonNull(reservations, "reservations").stream()
                .filter(Reservation::isActive)
                .map(reservation -> reservation.quantity().amount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

}