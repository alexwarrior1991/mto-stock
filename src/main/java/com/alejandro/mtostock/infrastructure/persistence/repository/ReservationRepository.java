package com.alejandro.mtostock.infrastructure.persistence.repository;

import com.alejandro.mtostock.infrastructure.persistence.entity.Reservation;
import com.alejandro.mtostock.infrastructure.persistence.entity.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Thin Spring Data repository for reservation aggregate persistence and specification-based search.
 */
public interface ReservationRepository extends JpaRepository<Reservation, UUID>, JpaSpecificationExecutor<Reservation> {

    /**
     * La fila de la reserva bloqueada ({@code select ... for update}) hasta el final de la transaccion.
     * Es lo que serializa las transiciones de una misma reserva (modificar, cancelar, liberar, consumir):
     * la segunda espera a la primera y la lee ya terminada, asi que no pasa la validacion de "activa" ni
     * descuenta lo reservado otra vez. Tiene que ser la <b>primera</b> lectura de la reserva en la
     * transaccion: una entidad ya cargada sin bloqueo se quedaria con el estado viejo. Orden de
     * bloqueo: reserva, despues material, despues {@code inventory_balance}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Reservation r where r.id = :id")
    Optional<Reservation> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Aggregates active reservation quantities because reservations reduce availability without changing stock.
     */
    @Query("""
            select coalesce(sum(reservation.quantity), :zero)
            from Reservation reservation
            where reservation.material.id = :materialId
              and (:warehouseId is null or reservation.warehouse.id = :warehouseId)
              and reservation.status = :status
            """)
    BigDecimal calculateReservedQuantityByStatus(
            @Param("materialId") UUID materialId,
            @Param("warehouseId") UUID warehouseId,
            @Param("status") ReservationStatus status,
            @Param("zero") BigDecimal zero
    );

    default BigDecimal calculateActiveReservedQuantity(UUID materialId, UUID warehouseId, BigDecimal zero) {
        return calculateReservedQuantityByStatus(materialId, warehouseId, ReservationStatus.ACTIVE, zero);
    }
}