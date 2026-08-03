package com.alejandro.mtostock.infrastructure.persistence.repository;

import com.alejandro.mtostock.infrastructure.persistence.entity.Assembly;
import com.alejandro.mtostock.infrastructure.persistence.entity.AssemblyComponent;
import com.alejandro.mtostock.infrastructure.persistence.entity.AuditableEntity;
import com.alejandro.mtostock.infrastructure.persistence.entity.Material;
import com.alejandro.mtostock.infrastructure.persistence.entity.Project;
import com.alejandro.mtostock.infrastructure.persistence.entity.Reservation;
import com.alejandro.mtostock.infrastructure.persistence.entity.ReservationStatus;
import com.alejandro.mtostock.infrastructure.persistence.entity.StockMovement;
import com.alejandro.mtostock.infrastructure.persistence.entity.StockMovementType;
import com.alejandro.mtostock.infrastructure.persistence.entity.Warehouse;
import com.alejandro.mtostock.infrastructure.persistence.specification.AssemblySpecification;
import com.alejandro.mtostock.infrastructure.persistence.specification.MaterialSpecification;
import com.alejandro.mtostock.infrastructure.persistence.specification.ReservationSpecification;
import com.alejandro.mtostock.infrastructure.persistence.specification.StockMovementSpecification;
import com.alejandro.mtostock.support.PostgreSQLTestContainer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class InventoryRepositoryDataJpaTest extends PostgreSQLTestContainer {

    @DynamicPropertySource
    static void postgreSQLProperties(DynamicPropertyRegistry registry) {
        registerPostgreSQLProperties(registry);
    }

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private MaterialRepository materialRepository;

    @Autowired
    private WarehouseRepository warehouseRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private AssemblyRepository assemblyRepository;

    @Autowired
    private StockMovementRepository stockMovementRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Test
    void specificationsFilterMaterialsAssembliesReservationsAndMovements() {
        Material material = persist(material("MAT-FILTER", "Copper contact wire"));
        Material inactiveMaterial = persist(material("MAT-INACTIVE", "Inactive material"));
        inactiveMaterial.setActive(false);
        Warehouse warehouse = persist(warehouse("WH-FILTER"));
        Project project = persist(project("PRJ-FILTER"));
        Assembly assembly = persist(assembly("ASM-FILTER", material));
        Reservation reservation = persist(reservation(material, warehouse, project, "2.000000", ReservationStatus.ACTIVE));
        persist(movement(material, warehouse, project, StockMovementType.ENTRY, "10.000000", Instant.parse("2026-08-01T10:00:00Z")));
        persist(movement(material, warehouse, project, StockMovementType.OUTPUT, "3.000000", Instant.parse("2026-08-01T11:00:00Z")));
        flushAndClear();

        assertEquals(1, materialRepository.findAll(MaterialSpecification.codeContains("filter")
                .and(MaterialSpecification.activeEquals(true))).size());
        assertEquals(1, assemblyRepository.findAll(AssemblySpecification.nameContains("assembly")
                .and(AssemblySpecification.activeEquals(true))).size());
        assertEquals(reservation.getId(), reservationRepository.findAll(ReservationSpecification.warehouseIdEquals(warehouse.getId())
                .and(ReservationSpecification.projectIdEquals(project.getId()))
                .and(ReservationSpecification.statusEquals(ReservationStatus.ACTIVE))).getFirst().getId());
        assertEquals(1, stockMovementRepository.findAll(StockMovementSpecification.materialIdEquals(material.getId())
                .and(StockMovementSpecification.typeEquals(StockMovementType.OUTPUT))).size());
        assertTrue(assemblyRepository.findWithComponentsById(assembly.getId()).orElseThrow().getComponents().stream()
                .anyMatch(component -> component.getMaterial().getCode().equals("MAT-FILTER")));
    }

    @Test
    void customQueriesAggregateMovementsAndActiveReservations() {
        Material material = persist(material("MAT-AGG", "Aggregate material"));
        Warehouse warehouse = persist(warehouse("WH-AGG"));
        Project project = persist(project("PRJ-AGG"));
        persist(movement(material, warehouse, project, StockMovementType.ENTRY, "12.000000", Instant.parse("2026-08-01T09:00:00Z")));
        persist(movement(material, warehouse, project, StockMovementType.OUTPUT, "5.000000", Instant.parse("2026-08-01T10:00:00Z")));
        persist(reservation(material, warehouse, project, "2.000000", ReservationStatus.ACTIVE));
        persist(reservation(material, warehouse, project, "4.000000", ReservationStatus.CANCELLED));
        flushAndClear();

        BigDecimal stock = stockMovementRepository.calculateSignedQuantity(
                material.getId(),
                warehouse.getId(),
                null,
                java.util.List.of(StockMovementType.ENTRY, StockMovementType.POSITIVE_ADJUSTMENT, StockMovementType.INCOMING_TRANSFER),
                BigDecimal.ZERO
        );
        BigDecimal reserved = reservationRepository.calculateActiveReservedQuantity(material.getId(), warehouse.getId(), BigDecimal.ZERO);

        assertEquals(0, new BigDecimal("7.000000").compareTo(stock));
        assertEquals(0, new BigDecimal("2.000000").compareTo(reserved));
    }

    @Test
    void relationshipsAndDatabaseConstraintsAreEnforced() {
        Material material = persist(material("MAT-REL", "Relationship material"));
        Warehouse warehouse = persist(warehouse("WH-REL"));
        Project project = persist(project("PRJ-REL"));
        persist(reservation(material, warehouse, project, "1.000000", ReservationStatus.ACTIVE));
        flushAndClear();

        Reservation stored = reservationRepository.findAll(ReservationSpecification.materialIdEquals(material.getId())).getFirst();

        assertEquals("MAT-REL", stored.getMaterial().getCode());
        assertThrows(PersistenceException.class, () -> {
            persist(material("MAT-REL", "Duplicate material"));
            entityManager.flush();
        });
    }

    private <T> T persist(T entity) {
        if (entity instanceof AuditableEntity auditableEntity) {
            audit(auditableEntity);
        }
        entityManager.persist(entity);
        return entity;
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private static void audit(AuditableEntity entity) {
        Instant now = Instant.parse("2026-08-01T08:00:00Z");
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setCreatedBy("repository-test");
        entity.setUpdatedBy("repository-test");
    }

    private static Material material(String code, String name) {
        return Material.builder()
                .code(code)
                .name(name)
                .unitOfMeasure("unit")
                .minimumStockLevel(BigDecimal.ZERO)
                .build();
    }

    private static Warehouse warehouse(String code) {
        return Warehouse.builder()
                .code(code)
                .name("Warehouse " + code)
                .build();
    }

    private static Project project(String code) {
        return Project.builder()
                .code(code)
                .name("Project " + code)
                .build();
    }

    private static Assembly assembly(String code, Material material) {
        Assembly assembly = Assembly.builder()
                .code(code)
                .name("Assembly " + code)
                .build();
        AssemblyComponent component = AssemblyComponent.builder()
                .material(material)
                .quantity(new BigDecimal("2.000000"))
                .build();
        audit(component);
        assembly.addComponent(component);
        return assembly;
    }

    private static StockMovement movement(Material material,
                                          Warehouse warehouse,
                                          Project project,
                                          StockMovementType type,
                                          String quantity,
                                          Instant occurredAt) {
        return StockMovement.builder()
                .material(material)
                .warehouse(warehouse)
                .project(project)
                .type(type)
                .quantity(new BigDecimal(quantity))
                .occurredAt(occurredAt)
                .build();
    }

    private static Reservation reservation(Material material,
                                           Warehouse warehouse,
                                           Project project,
                                           String quantity,
                                           ReservationStatus status) {
        Reservation reservation = Reservation.builder()
                .material(material)
                .warehouse(warehouse)
                .project(project)
                .quantity(new BigDecimal(quantity))
                .status(status)
                .build();
        if (status != ReservationStatus.ACTIVE) {
            reservation.setReleasedAt(Instant.parse("2026-08-01T12:00:00Z"));
        }
        return reservation;
    }
}