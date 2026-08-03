package com.alejandro.mtostock.infrastructure.persistence.repository;

import com.alejandro.mtostock.infrastructure.persistence.entity.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Thin Spring Data repository for reservation aggregate persistence and specification-based search.
 */
public interface ReservationRepository extends JpaRepository<Reservation, UUID>, JpaSpecificationExecutor<Reservation> {

    /**
     * Aggregates active reservation quantities because reservations reduce availability without changing stock.
     */
    @Query("""
            select coalesce(sum(reservation.quantity), :zero)
            from Reservation reservation
            where reservation.material.id = :materialId
              and (:warehouseId is null or reservation.warehouse.id = :warehouseId)
              and reservation.status = com.alejandro.mtostock.infrastructure.persistence.entity.ReservationStatus.ACTIVE
            """)
    BigDecimal calculateActiveReservedQuantity(
            @Param("materialId") UUID materialId,
            @Param("warehouseId") UUID warehouseId,
            @Param("zero") BigDecimal zero
    );
}