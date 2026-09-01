package com.alejandro.mtostock.infrastructure.persistence;

import com.alejandro.mtostock.infrastructure.persistence.entity.Assembly;
import com.alejandro.mtostock.infrastructure.persistence.entity.Material;
import com.alejandro.mtostock.infrastructure.persistence.entity.Project;
import com.alejandro.mtostock.infrastructure.persistence.entity.Reservation;
import com.alejandro.mtostock.infrastructure.persistence.entity.ReservationStatus;
import com.alejandro.mtostock.infrastructure.persistence.entity.StockMovement;
import com.alejandro.mtostock.infrastructure.persistence.entity.StockMovementType;
import com.alejandro.mtostock.infrastructure.persistence.entity.Supplier;
import com.alejandro.mtostock.infrastructure.persistence.entity.Warehouse;
import com.alejandro.mtostock.infrastructure.persistence.repository.AssemblyRepository;
import com.alejandro.mtostock.infrastructure.persistence.repository.MaterialRepository;
import com.alejandro.mtostock.infrastructure.persistence.repository.ProjectRepository;
import com.alejandro.mtostock.infrastructure.persistence.repository.ReservationRepository;
import com.alejandro.mtostock.infrastructure.persistence.repository.StockMovementRepository;
import com.alejandro.mtostock.infrastructure.persistence.repository.SupplierRepository;
import com.alejandro.mtostock.infrastructure.persistence.repository.WarehouseRepository;
import com.alejandro.mtostock.infrastructure.persistence.specification.AssemblySpecification;
import com.alejandro.mtostock.infrastructure.persistence.specification.MaterialSpecification;
import com.alejandro.mtostock.infrastructure.persistence.specification.ReservationSpecification;
import com.alejandro.mtostock.infrastructure.persistence.specification.StockMovementSpecification;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceLayerTest {

    @Test
    void aggregateRepositoriesExposeOnlyJpaPersistenceContracts() {
        assertJpaRepository(MaterialRepository.class);
        assertSpecificationRepository(MaterialRepository.class);
        assertJpaRepository(AssemblyRepository.class);
        assertSpecificationRepository(AssemblyRepository.class);
        assertJpaRepository(WarehouseRepository.class);
        assertJpaRepository(StockMovementRepository.class);
        assertSpecificationRepository(StockMovementRepository.class);
        assertJpaRepository(ReservationRepository.class);
        assertSpecificationRepository(ReservationRepository.class);
        assertJpaRepository(SupplierRepository.class);
        assertJpaRepository(ProjectRepository.class);
    }

    @Test
    void specificationsAreComposableForSearchableAggregates() {
        UUID warehouseId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();

        Specification<Material> materialSpecification = Specification.where(MaterialSpecification.codeContains("MAT"))
                .and(MaterialSpecification.nameContains("wire"))
                .and(MaterialSpecification.activeEquals(true))
                .and(MaterialSpecification.storedInWarehouse(warehouseId))
                .and(MaterialSpecification.stockBelowMinimum(warehouseId));
        Specification<Assembly> assemblySpecification = Specification.where(AssemblySpecification.codeContains("ASM"))
                .and(AssemblySpecification.nameContains("section"))
                .and(AssemblySpecification.activeEquals(true));
        Specification<StockMovement> stockMovementSpecification = Specification.where(StockMovementSpecification.typeEquals(StockMovementType.ENTRY))
                .and(StockMovementSpecification.warehouseIdEquals(warehouseId))
                .and(StockMovementSpecification.projectIdEquals(projectId))
                .and(StockMovementSpecification.materialIdEquals(materialId))
                .and(StockMovementSpecification.occurredAtFrom(Instant.parse("2026-08-01T00:00:00Z")))
                .and(StockMovementSpecification.occurredAtTo(Instant.parse("2026-08-31T23:59:59Z")))
                .and(StockMovementSpecification.createdByContains("planner"));
        Specification<Reservation> reservationSpecification = Specification.where(ReservationSpecification.warehouseIdEquals(warehouseId))
                .and(ReservationSpecification.statusEquals(ReservationStatus.ACTIVE))
                .and(ReservationSpecification.projectIdEquals(projectId))
                .and(ReservationSpecification.materialIdEquals(materialId));

        assertNotNull(materialSpecification);
        assertNotNull(assemblySpecification);
        assertNotNull(stockMovementSpecification);
        assertNotNull(reservationSpecification);
    }

    private static void assertJpaRepository(Class<?> repositoryType) {
        assertTrue(JpaRepository.class.isAssignableFrom(repositoryType));
    }

    private static void assertSpecificationRepository(Class<?> repositoryType) {
        assertTrue(JpaSpecificationExecutor.class.isAssignableFrom(repositoryType));
    }
}