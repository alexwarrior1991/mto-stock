package com.alejandro.mtostock.application.service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Application service responsible for every current stock balance mutation.
 */
public interface InventoryBalanceService {

    void increasePhysical(UUID materialId, UUID warehouseId, BigDecimal quantity);

    void decreasePhysicalAndAvailable(UUID materialId, UUID warehouseId, BigDecimal quantity);

    void reserve(UUID materialId, UUID warehouseId, BigDecimal quantity);

    /**
     * Mueve stock fisico entre dos almacenes del mismo material en una sola operacion: el total del
     * material no cambia, asi que no puede cruzar por debajo del minimo, y el bloqueo del material se
     * toma una vez para las dos filas.
     */
    void transfer(UUID materialId, UUID sourceWarehouseId, UUID targetWarehouseId, BigDecimal quantity);

    /**
     * Lleva lo reservado por una reserva activa de lo que tenia (material, almacen, cantidad) a lo que
     * pasa a tener, en una sola operacion. Bloquea primero el material -los dos, por orden de id, si
     * cambia- y solo despues toca los saldos, como toda operacion que reduce el disponible; y compara
     * con el minimo el disponible total del material antes y despues de la operacion entera, no de
     * cada mitad: liberar y volver a reservar por separado avisaba de un cruce que no habia.
     */
    void moveReserved(UUID fromMaterialId, UUID fromWarehouseId, BigDecimal fromQuantity,
                      UUID toMaterialId, UUID toWarehouseId, BigDecimal toQuantity);

    void releaseReserved(UUID materialId, UUID warehouseId, BigDecimal quantity);

    void consumeReserved(UUID materialId, UUID warehouseId, BigDecimal quantity);
}