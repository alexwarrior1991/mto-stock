package com.alejandro.mtostock.infrastructure.persistence.repository;

import com.alejandro.mtostock.infrastructure.persistence.entity.InventoryBalance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for the current stock projection and atomic balance updates.
 */
public interface InventoryBalanceRepository extends JpaRepository<InventoryBalance, UUID> {

    Optional<InventoryBalance> findByMaterialIdAndWarehouseId(UUID materialId, UUID warehouseId);

    @Query("""
            select coalesce(sum(balance.physicalQuantity), :zero)
            from InventoryBalance balance
            where balance.material.id = :materialId
              and (:warehouseId is null or balance.warehouse.id = :warehouseId)
            """)
    BigDecimal calculatePhysicalQuantity(
            @Param("materialId") UUID materialId,
            @Param("warehouseId") UUID warehouseId,
            @Param("zero") BigDecimal zero
    );

    @Query("""
            select coalesce(sum(balance.reservedQuantity), :zero)
            from InventoryBalance balance
            where balance.material.id = :materialId
              and (:warehouseId is null or balance.warehouse.id = :warehouseId)
            """)
    BigDecimal calculateReservedQuantity(
            @Param("materialId") UUID materialId,
            @Param("warehouseId") UUID warehouseId,
            @Param("zero") BigDecimal zero
    );

    @Query("""
            select coalesce(sum(balance.availableQuantity), :zero)
            from InventoryBalance balance
            where balance.material.id = :materialId
              and (:warehouseId is null or balance.warehouse.id = :warehouseId)
            """)
    BigDecimal calculateAvailableQuantity(
            @Param("materialId") UUID materialId,
            @Param("warehouseId") UUID warehouseId,
            @Param("zero") BigDecimal zero
    );

    @Modifying
    @Query(value = """
            insert into inventory_balance (
                id,
                material_id,
                warehouse_id,
                physical_quantity,
                reserved_quantity,
                available_quantity,
                version,
                created_at,
                updated_at,
                created_by,
                updated_by
            ) values (
                gen_random_uuid(),
                :materialId,
                :warehouseId,
                0,
                0,
                0,
                0,
                now(),
                now(),
                :actor,
                :actor
            ) on conflict (material_id, warehouse_id) do nothing
            """, nativeQuery = true)
    int insertZeroBalanceIfMissing(
            @Param("materialId") UUID materialId,
            @Param("warehouseId") UUID warehouseId,
            @Param("actor") String actor
    );

    @Modifying
    @Query(value = """
            update inventory_balance
               set physical_quantity = physical_quantity + :quantity,
                   available_quantity = available_quantity + :quantity,
                   version = version + 1,
                   updated_at = now(),
                   updated_by = :actor
             where material_id = :materialId
               and warehouse_id = :warehouseId
            """, nativeQuery = true)
    int increasePhysical(
            @Param("materialId") UUID materialId,
            @Param("warehouseId") UUID warehouseId,
            @Param("quantity") BigDecimal quantity,
            @Param("actor") String actor
    );

    @Modifying
    @Query(value = """
            update inventory_balance
               set physical_quantity = physical_quantity - :quantity,
                   available_quantity = available_quantity - :quantity,
                   version = version + 1,
                   updated_at = now(),
                   updated_by = :actor
             where material_id = :materialId
               and warehouse_id = :warehouseId
               and physical_quantity >= :quantity
               and available_quantity >= :quantity
            """, nativeQuery = true)
    int decreasePhysicalAndAvailable(
            @Param("materialId") UUID materialId,
            @Param("warehouseId") UUID warehouseId,
            @Param("quantity") BigDecimal quantity,
            @Param("actor") String actor
    );

    @Modifying
    @Query(value = """
            update inventory_balance
               set reserved_quantity = reserved_quantity + :quantity,
                   available_quantity = available_quantity - :quantity,
                   version = version + 1,
                   updated_at = now(),
                   updated_by = :actor
             where material_id = :materialId
               and warehouse_id = :warehouseId
               and available_quantity >= :quantity
            """, nativeQuery = true)
    int reserve(
            @Param("materialId") UUID materialId,
            @Param("warehouseId") UUID warehouseId,
            @Param("quantity") BigDecimal quantity,
            @Param("actor") String actor
    );

    @Modifying
    @Query(value = """
            update inventory_balance
               set reserved_quantity = reserved_quantity - :quantity,
                   available_quantity = available_quantity + :quantity,
                   version = version + 1,
                   updated_at = now(),
                   updated_by = :actor
             where material_id = :materialId
               and warehouse_id = :warehouseId
               and reserved_quantity >= :quantity
            """, nativeQuery = true)
    int releaseReserved(
            @Param("materialId") UUID materialId,
            @Param("warehouseId") UUID warehouseId,
            @Param("quantity") BigDecimal quantity,
            @Param("actor") String actor
    );

    @Modifying
    @Query(value = """
            update inventory_balance
               set physical_quantity = physical_quantity - :quantity,
                   reserved_quantity = reserved_quantity - :quantity,
                   version = version + 1,
                   updated_at = now(),
                   updated_by = :actor
             where material_id = :materialId
               and warehouse_id = :warehouseId
               and physical_quantity >= :quantity
               and reserved_quantity >= :quantity
            """, nativeQuery = true)
    int consumeReserved(
            @Param("materialId") UUID materialId,
            @Param("warehouseId") UUID warehouseId,
            @Param("quantity") BigDecimal quantity,
            @Param("actor") String actor
    );
}