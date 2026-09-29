package com.alejandro.mtostock.application.service.impl;

import com.alejandro.mtostock.application.exception.InsufficientStockException;
import com.alejandro.mtostock.application.exception.NotFoundException;
import com.alejandro.mtostock.application.exception.ReservationException;
import com.alejandro.mtostock.application.exception.ValidationException;
import com.alejandro.mtostock.application.service.DomainEventPublisher;
import com.alejandro.mtostock.application.service.InventoryBalanceService;
import com.alejandro.mtostock.infrastructure.persistence.entity.Material;
import com.alejandro.mtostock.infrastructure.persistence.repository.InventoryBalanceRepository;
import com.alejandro.mtostock.infrastructure.persistence.repository.MaterialRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.AuditorAware;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Coordinates atomic inventory balance updates and translates failed updates into business errors.
 *
 * <p>Es tambien quien sabe cuando un material cae por debajo de su minimo, porque es el unico sitio
 * por el que pasa toda escritura del disponible. Cada operacion que lo reduce (una salida, un
 * ajuste negativo, una reserva) bloquea antes la fila del material ({@code select ... for update}),
 * actualiza el saldo y compara el disponible total del material -la suma de sus almacenes- con
 * {@code minimum_stock_level}: si estaba por encima o igual antes y por debajo despues, publica
 * {@code material.below-minimum}. Con el material bloqueado, dos salidas concurrentes del mismo
 * material se serializan y solo una de ellas ve el cruce; sin el bloqueo las dos podrian leer la
 * suma en el mismo instante y avisar las dos, o ninguna. El orden de bloqueo es siempre material y
 * despues saldo, tambien en las transferencias, que bloquean el material aunque no cambien el
 * total.</p>
 */
@Service
@RequiredArgsConstructor
class InventoryBalanceServiceImpl implements InventoryBalanceService {

    private static final String SYSTEM_ACTOR = "system";
    private static final int MAX_ACTOR_LENGTH = 100;

    private final InventoryBalanceRepository inventoryBalanceRepository;
    private final MaterialRepository materialRepository;
    private final DomainEventPublisher domainEventPublisher;
    private final AuditorAware<String> auditorAware;

    @Override
    @Transactional
    public void increasePhysical(UUID materialId, UUID warehouseId, BigDecimal quantity) {
        validatePositiveQuantity(quantity);
        String actor = currentActor();
        inventoryBalanceRepository.insertZeroBalanceIfMissing(materialId, warehouseId, actor);
        int updatedRows = inventoryBalanceRepository.increasePhysical(materialId, warehouseId, quantity, actor);
        if (updatedRows == 0) {
            throw new ReservationException("Inventory balance could not be increased");
        }
    }

    @Override
    @Transactional
    public void decreasePhysicalAndAvailable(UUID materialId, UUID warehouseId, BigDecimal quantity) {
        validatePositiveQuantity(quantity);
        Material material = lockMaterial(materialId);
        int updatedRows = inventoryBalanceRepository.decreasePhysicalAndAvailable(materialId, warehouseId, quantity, currentActor());
        if (updatedRows == 0) {
            throw insufficientStock(materialId, warehouseId, quantity);
        }
        publishIfCrossedBelowMinimum(material, warehouseId, quantity, StockEvents.OPERATION_OUTPUT);
    }

    @Override
    @Transactional
    public void reserve(UUID materialId, UUID warehouseId, BigDecimal quantity) {
        validatePositiveQuantity(quantity);
        Material material = lockMaterial(materialId);
        int updatedRows = inventoryBalanceRepository.reserve(materialId, warehouseId, quantity, currentActor());
        if (updatedRows == 0) {
            throw insufficientStock(materialId, warehouseId, quantity);
        }
        publishIfCrossedBelowMinimum(material, warehouseId, quantity, StockEvents.OPERATION_RESERVATION);
    }

    @Override
    @Transactional
    public void transfer(UUID materialId, UUID sourceWarehouseId, UUID targetWarehouseId, BigDecimal quantity) {
        validatePositiveQuantity(quantity);
        lockMaterial(materialId);
        String actor = currentActor();
        if (inventoryBalanceRepository.decreasePhysicalAndAvailable(materialId, sourceWarehouseId, quantity, actor) == 0) {
            throw insufficientStock(materialId, sourceWarehouseId, quantity);
        }
        inventoryBalanceRepository.insertZeroBalanceIfMissing(materialId, targetWarehouseId, actor);
        if (inventoryBalanceRepository.increasePhysical(materialId, targetWarehouseId, quantity, actor) == 0) {
            throw new ReservationException("Inventory balance could not be increased");
        }
    }

    @Override
    @Transactional
    public void releaseReserved(UUID materialId, UUID warehouseId, BigDecimal quantity) {
        validatePositiveQuantity(quantity);
        int updatedRows = inventoryBalanceRepository.releaseReserved(materialId, warehouseId, quantity, currentActor());
        if (updatedRows == 0) {
            throw new ReservationException("Reserved stock could not be released");
        }
    }

    @Override
    @Transactional
    public void consumeReserved(UUID materialId, UUID warehouseId, BigDecimal quantity) {
        validatePositiveQuantity(quantity);
        int updatedRows = inventoryBalanceRepository.consumeReserved(materialId, warehouseId, quantity, currentActor());
        if (updatedRows == 0) {
            throw new ReservationException("Reserved stock could not be consumed");
        }
    }

    /**
     * La fila del material, bloqueada hasta el final de la transaccion: serializa las operaciones
     * que reducen el disponible del mismo material, para que el cruce por debajo del minimo lo vea
     * exactamente una de ellas.
     */
    private Material lockMaterial(UUID materialId) {
        return materialRepository.findByIdForUpdate(materialId)
                .orElseThrow(() -> new NotFoundException("Material", materialId));
    }

    /**
     * Un minimo de cero (o ninguno) nunca se cruza, asi que no se suma nada. Se compara el
     * disponible total del material y no el del almacen: es la misma vista que sirve
     * {@code GET /materials/{id}/stock} sin almacen y la que lista los materiales bajo minimo.
     */
    private void publishIfCrossedBelowMinimum(Material material, UUID warehouseId, BigDecimal quantity, String operation) {
        BigDecimal minimum = material.getMinimumStockLevel();
        if (minimum == null || minimum.signum() <= 0) {
            return;
        }
        BigDecimal after = inventoryBalanceRepository.calculateAvailableQuantity(material.getId(), null, BigDecimal.ZERO);
        BigDecimal before = after.add(quantity);
        if (after.compareTo(minimum) < 0 && before.compareTo(minimum) >= 0) {
            domainEventPublisher.publish(StockEvents.materialBelowMinimum(material, warehouseId, operation, quantity, before, after));
        }
    }

    private String currentActor() {
        return auditorAware.getCurrentAuditor()
                .map(String::trim)
                .filter(actor -> !actor.isBlank())
                .map(this::truncateActor)
                .orElse(SYSTEM_ACTOR);
    }

    private String truncateActor(String actor) {
        if (actor.length() > MAX_ACTOR_LENGTH) {
            return actor.substring(0, MAX_ACTOR_LENGTH);
        }
        return actor;
    }

    private void validatePositiveQuantity(BigDecimal quantity) {
        if (quantity == null || quantity.signum() <= 0) {
            throw new ValidationException("Quantity must be greater than zero");
        }
    }

    private InsufficientStockException insufficientStock(UUID materialId, UUID warehouseId, BigDecimal quantity) {
        BigDecimal available = inventoryBalanceRepository.calculateAvailableQuantity(materialId, warehouseId, BigDecimal.ZERO);
        return new InsufficientStockException(materialId, warehouseId, quantity, available);
    }
}
