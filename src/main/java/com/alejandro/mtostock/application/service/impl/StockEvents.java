package com.alejandro.mtostock.application.service.impl;

import com.alejandro.mtostock.application.dto.messaging.DomainEvent;
import com.alejandro.mtostock.application.dto.stock.StockAdjustmentDirection;
import com.alejandro.mtostock.infrastructure.persistence.entity.Material;
import com.alejandro.mtostock.infrastructure.persistence.entity.Project;
import com.alejandro.mtostock.infrastructure.persistence.entity.Reservation;
import com.alejandro.mtostock.infrastructure.persistence.entity.StockMovement;
import com.alejandro.mtostock.infrastructure.persistence.entity.Warehouse;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Los eventos que este servicio cuenta de si mismo: su entidad, su nombre y lo que viaja en
 * {@code values}. Es la definicion del contrato con {@code mto-notification} ({@code docs/06-messaging.md}),
 * y por eso vive en un solo sitio y es publica: {@code MessagingContractExamplesTest} la usa para
 * comprobar que cada ejemplo versionado en {@code docs/messaging/examples} sigue siendo lo que se
 * publica.
 *
 * <p>En el contrato solo se anaden claves. Lo que viaja es lo que hace falta para avisar y para
 * enlazar (codigos, ids, cantidades, quien), nunca nada que huela a secreto ({@link DomainEvent} lo
 * rechaza). Un valor nulo viaja como nulo: para el consumidor «sin referencia externa» es
 * informacion. El material, el almacen y el proyecto viajan siempre con las mismas claves
 * ({@code materialCode}, {@code warehouseCode}, {@code projectCode}...) sea cual sea el evento, para
 * que una regla los lea igual en todos.</p>
 */
public final class StockEvents {

    public static final String MATERIAL = "material";
    public static final String RESERVATION = "reservation";
    public static final String ADJUSTMENT = "adjustment";

    public static final String BELOW_MINIMUM = "below-minimum";
    public static final String CANCELLED = "cancelled";
    public static final String RELEASED = "released";
    public static final String REGISTERED = "registered";

    /** Lo que dejo el material por debajo de su minimo: una salida fisica (salida o ajuste negativo) o una reserva. */
    public static final String OPERATION_OUTPUT = "OUTPUT";
    public static final String OPERATION_RESERVATION = "RESERVATION";

    private StockEvents() {
    }

    // ----------------------------------------------------------------------------- materials

    /**
     * El disponible total del material (la suma de sus almacenes, la misma vista que
     * {@code GET /materials/{id}/stock} sin almacen) acaba de cruzar por debajo de
     * {@code minimum_stock_level}: estaba por encima o igual antes de esta operacion y por debajo
     * despues. Solo al cruzar: una segunda salida ya por debajo no vuelve a avisar.
     */
    public static DomainEvent materialBelowMinimum(Material material, UUID warehouseId, String operation, BigDecimal quantity,
                                                   BigDecimal availableBefore, BigDecimal availableAfter) {
        Map<String, Object> values = new LinkedHashMap<>();
        material(values, material);
        values.put("minimumStockLevel", material.getMinimumStockLevel());
        values.put("availableBefore", availableBefore);
        values.put("availableAfter", availableAfter);
        values.put("quantity", quantity);
        values.put("operation", operation);
        values.put("warehouseId", warehouseId);
        return new DomainEvent(MATERIAL, id(material.getId()), BELOW_MINIMUM, values);
    }

    // -------------------------------------------------------------------------- reservations

    /** {@code DELETE /reservations/{id}}: la reserva cancelada, con quien la creo ({@code createdBy}) para saber si la toca otro. */
    public static DomainEvent reservationCancelled(Reservation reservation) {
        return reservation(reservation, CANCELLED);
    }

    /** {@code POST /reservations/{id}/release}: la reserva liberada sin consumir. */
    public static DomainEvent reservationReleased(Reservation reservation) {
        return reservation(reservation, RELEASED);
    }

    private static DomainEvent reservation(Reservation reservation, String eventName) {
        Map<String, Object> values = new LinkedHashMap<>();
        material(values, reservation.getMaterial());
        warehouse(values, reservation.getWarehouse());
        project(values, reservation.getProject());
        values.put("quantity", reservation.getQuantity());
        values.put("status", name(reservation.getStatus()));
        values.put("reservedAt", reservation.getReservedAt());
        values.put("releasedAt", reservation.getReleasedAt());
        values.put("createdBy", reservation.getCreatedBy());
        values.put("createdAt", reservation.getCreatedAt());
        return new DomainEvent(RESERVATION, id(reservation.getId()), eventName, values);
    }

    // --------------------------------------------------------------------------- adjustments

    /** {@code POST /movements/adjustments}: el apunte del ajuste, con su direccion y lo que el operario escribio. */
    public static DomainEvent adjustmentRegistered(StockMovement movement, StockAdjustmentDirection direction) {
        Map<String, Object> values = new LinkedHashMap<>();
        material(values, movement.getMaterial());
        warehouse(values, movement.getWarehouse());
        values.put("direction", name(direction));
        values.put("movementType", name(movement.getType()));
        values.put("quantity", movement.getQuantity());
        values.put("signedQuantity", movement.signedQuantity());
        values.put("occurredAt", movement.getOccurredAt());
        values.put("externalReference", movement.getExternalReference());
        values.put("notes", movement.getNotes());
        return new DomainEvent(ADJUSTMENT, id(movement.getId()), REGISTERED, values);
    }

    // ------------------------------------------------------------------------------- helpers

    private static void material(Map<String, Object> values, Material material) {
        values.put("materialId", material.getId());
        values.put("materialCode", material.getCode());
        values.put("materialName", material.getName());
        values.put("unit", material.getUnitOfMeasure());
    }

    private static void warehouse(Map<String, Object> values, Warehouse warehouse) {
        values.put("warehouseId", warehouse == null ? null : warehouse.getId());
        values.put("warehouseCode", warehouse == null ? null : warehouse.getCode());
        values.put("warehouseName", warehouse == null ? null : warehouse.getName());
    }

    private static void project(Map<String, Object> values, Project project) {
        values.put("projectId", project == null ? null : project.getId());
        values.put("projectCode", project == null ? null : project.getCode());
        values.put("projectName", project == null ? null : project.getName());
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static String id(UUID id) {
        return id == null ? null : id.toString();
    }
}
