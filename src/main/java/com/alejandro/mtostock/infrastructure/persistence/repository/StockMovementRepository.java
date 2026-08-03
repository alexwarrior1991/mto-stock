package com.alejandro.mtostock.infrastructure.persistence.repository;

import com.alejandro.mtostock.infrastructure.persistence.entity.StockMovement;
import com.alejandro.mtostock.infrastructure.persistence.entity.StockMovementType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.UUID;

/**
 * Thin Spring Data repository for append-only stock ledger persistence and specification-based search.
 */
public interface StockMovementRepository extends JpaRepository<StockMovement, UUID>, JpaSpecificationExecutor<StockMovement> {

    /**
     * Aggregates the signed stock quantity from the movement ledger without relying on a stored stock column.
     */
    @Query("""
            select coalesce(sum(
                case when movement.type in :positiveTypes then movement.quantity else -movement.quantity end
            ), :zero)
            from StockMovement movement
            where movement.material.id = :materialId
              and (:warehouseId is null or movement.warehouse.id = :warehouseId)
              and movement.occurredAt <= coalesce(:asOf, movement.occurredAt)
            """)
    BigDecimal calculateSignedQuantity(
            @Param("materialId") UUID materialId,
            @Param("warehouseId") UUID warehouseId,
            @Param("asOf") Instant asOf,
            @Param("positiveTypes") Collection<StockMovementType> positiveTypes,
            @Param("zero") BigDecimal zero
    );
}