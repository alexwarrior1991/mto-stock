package com.alejandro.mtostock.infrastructure.persistence.repository;

import com.alejandro.mtostock.infrastructure.persistence.entity.Assembly;
import com.alejandro.mtostock.infrastructure.persistence.entity.AssemblyComponent;
import com.alejandro.mtostock.infrastructure.persistence.entity.AuditableEntity;
import com.alejandro.mtostock.infrastructure.persistence.entity.IdempotentOperation;
import com.alejandro.mtostock.infrastructure.persistence.entity.IdempotentRequest;
import com.alejandro.mtostock.infrastructure.persistence.entity.InventoryBalance;
import com.alejandro.mtostock.infrastructure.persistence.entity.Material;
import com.alejandro.mtostock.infrastructure.persistence.entity.Project;
import com.alejandro.mtostock.infrastructure.persistence.entity.Reservation;
import com.alejandro.mtostock.infrastructure.persistence.entity.ReservationStatus;
import com.alejandro.mtostock.infrastructure.persistence.entity.StockMovement;
import com.alejandro.mtostock.infrastructure.persistence.entity.StockMovementType;
import com.alejandro.mtostock.infrastructure.persistence.entity.Supplier;
import com.alejandro.mtostock.infrastructure.persistence.entity.Warehouse;
import com.alejandro.mtostock.infrastructure.persistence.specification.AssemblySpecification;
import com.alejandro.mtostock.infrastructure.persistence.specification.MaterialSpecification;
import com.alejandro.mtostock.infrastructure.persistence.specification.ProjectSpecification;
import com.alejandro.mtostock.infrastructure.persistence.specification.ReservationSpecification;
import com.alejandro.mtostock.infrastructure.persistence.specification.StockMovementSpecification;
import com.alejandro.mtostock.infrastructure.persistence.specification.SupplierSpecification;
import com.alejandro.mtostock.infrastructure.persistence.specification.WarehouseSpecification;
import com.alejandro.mtostock.support.PostgreSQLTestContainer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.hibernate.Session;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
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
    private SupplierRepository supplierRepository;

    @Autowired
    private AssemblyRepository assemblyRepository;

    @Autowired
    private StockMovementRepository stockMovementRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private IdempotentRequestRepository idempotentRequestRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

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

    /**
     * La busqueda por texto de los catalogos: codigo o nombre, sin distinguir mayusculas, y en
     * blanco no filtra nada. Un solo helper para los cinco, asi que se prueban los cinco.
     */
    @Test
    void searchMatchesCodeOrNameCaseInsensitivelyOnEveryCatalogue() {
        Material byCode = persist(material("MAT-ZIRC", "Contact wire"));
        persist(material("MAT-STEEL", "Zirconium bracket"));
        persist(material("MAT-PLAIN", "Insulator"));
        persist(warehouse("WH-ZIRC"));
        persist(warehouse("WH-PLAIN"));
        persist(project("PRJ-ZIRC"));
        persist(project("PRJ-PLAIN"));
        persist(Supplier.builder().code("SUP-001").name("Zirconium Mills").build());
        persist(Supplier.builder().code("SUP-002").name("Plain Steel").build());
        persist(assembly("ASM-ZIRC", byCode));
        flushAndClear();

        assertEquals(2, materialRepository.findAll(MaterialSpecification.codeOrNameContains("zIrC")).size());
        assertEquals(1, warehouseRepository.findAll(WarehouseSpecification.codeOrNameContains("zirc")).size());
        assertEquals(1, projectRepository.findAll(ProjectSpecification.codeOrNameContains("ZIRC")).size());
        assertEquals(1, supplierRepository.findAll(SupplierSpecification.codeOrNameContains("mills")).size());
        assertEquals(1, assemblyRepository.findAll(AssemblySpecification.codeOrNameContains("zirc")).size());
        assertEquals(warehouseRepository.count(), warehouseRepository.findAll(WarehouseSpecification.codeOrNameContains("  ")).size());
        assertEquals(0, projectRepository.findAll(ProjectSpecification.codeOrNameContains("zirc")
                .and(ProjectSpecification.activeEquals(false))).size());
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
    void lowStockFilterReadsAvailableQuantityFromTheInventoryBalanceProjection() {
        Material lowMaterial = material("MAT-LOW", "Below minimum material");
        lowMaterial.setMinimumStockLevel(new BigDecimal("10.000000"));
        Material stockedMaterial = material("MAT-STOCKED", "Above minimum material");
        stockedMaterial.setMinimumStockLevel(new BigDecimal("10.000000"));
        Material reservedMaterial = material("MAT-RESERVED", "Fully reserved material");
        reservedMaterial.setMinimumStockLevel(new BigDecimal("10.000000"));
        persist(lowMaterial);
        persist(stockedMaterial);
        persist(reservedMaterial);
        Warehouse warehouse = persist(warehouse("WH-LOW"));
        // No stock_movement rows at all: the filter must answer from the projection, and the reserved
        // material must count as low even though its physical quantity is above the minimum.
        persist(balance(lowMaterial, warehouse, "5.000000", "0.000000"));
        persist(balance(stockedMaterial, warehouse, "20.000000", "0.000000"));
        persist(balance(reservedMaterial, warehouse, "20.000000", "15.000000"));
        flushAndClear();

        assertEquals(
                java.util.List.of("MAT-LOW", "MAT-RESERVED"),
                lowStockCodes(MaterialSpecification.stockBelowMinimum(warehouse.getId()))
        );
        assertEquals(
                java.util.List.of("MAT-LOW", "MAT-RESERVED"),
                lowStockCodes(MaterialSpecification.stockBelowMinimum(null))
        );
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

    /**
     * El alta y la modificacion de un paquete de ejecucion son la misma sentencia: la entrega es
     * at-least-once, asi que un alta reentregada tiene que actualizar en vez de reventar por la
     * restriccion unica.
     */
    @Test
    void masterDataUpsertCreatesTheProjectOnceAndUpdatesItAfterwards() {
        assertEquals(1, projectRepository.upsertFromMasterData(
                "mto-configuration", "42", "EP-42", "Tramo Sants-Sagrera", true, 10L));
        entityManager.clear();
        assertEquals(1, projectRepository.upsertFromMasterData(
                "mto-configuration", "42", "EP-42", "Tramo Sants-Sagrera (revisado)", false, 11L));
        entityManager.clear();

        Project project = projectRepository
                .findBySourceServiceAndSourceEntityId("mto-configuration", "42")
                .orElseThrow();
        assertEquals("EP-42", project.getCode());
        assertEquals("Tramo Sants-Sagrera (revisado)", project.getName());
        assertFalse(project.getActive());
        assertTrue(project.isSynchronized());
        assertEquals(1, projectRepository.count());
    }

    /**
     * Los proyectos creados a mano quedan con el origen a NULL, y PostgreSQL considera distintos dos
     * NULL en un indice unico: la restriccion de origen no les afecta por muchos que haya.
     */
    @Test
    void locallyCreatedProjectsAreUnaffectedByTheSourceUniqueConstraint() {
        persist(Project.builder().code("PRJ-001").name("Local one").build());
        persist(Project.builder().code("PRJ-002").name("Local two").build());
        projectRepository.upsertFromMasterData("mto-configuration", "42", "EP-42", "Sincronizado", true, 10L);
        entityManager.flush();
        entityManager.clear();

        assertEquals(3, projectRepository.count());
        assertFalse(projectRepository.findByCode("PRJ-001").orElseThrow().isSynchronized());
    }

    /**
     * La baja desactiva y no borra: reservation y stock_movement apuntan a project con on delete
     * restrict, de modo que un proyecto con historial no se podria borrar aunque se quisiera.
     */
    @Test
    void masterDataDeletionDeactivatesTheProjectAndKeepsItsHistory() {
        projectRepository.upsertFromMasterData("mto-configuration", "42", "EP-42", "Tramo", true, 10L);
        entityManager.clear();

        assertEquals(1, projectRepository.deactivateFromMasterData("mto-configuration", "42", 11L));
        entityManager.clear();

        Project project = projectRepository
                .findBySourceServiceAndSourceEntityId("mto-configuration", "42")
                .orElseThrow();
        assertFalse(project.getActive());
        // Un paquete que este servicio nunca vio no es un error.
        assertEquals(0, projectRepository.deactivateFromMasterData("mto-configuration", "99", 11L));
    }


    /**
     * El escenario que esto arregla: el evento 10 falla y se reprograma, el 11 llega y se aplica, y
     * cuando el 10 se reintenta ya no debe pisar al 11 con el nombre viejo.
     */
    @Test
    void aChangeArrivingBehindWhatWasAlreadyAppliedIsDiscarded() {
        projectRepository.upsertFromMasterData("mto-configuration", "42", "EP-42", "Nombre nuevo", true, 11L);
        entityManager.clear();

        assertEquals(0, projectRepository.upsertFromMasterData(
                "mto-configuration", "42", "EP-42", "Nombre viejo", true, 10L));
        entityManager.clear();

        Project project = projectRepository
                .findBySourceServiceAndSourceEntityId("mto-configuration", "42")
                .orElseThrow();
        assertEquals("Nombre nuevo", project.getName());
        assertEquals(11L, project.getSourceSequenceNumber());
    }

    /**
     * Un UPDATE retrasado detras de un DELETE reactivaba el proyecto. La baja adelanta la marca de
     * agua aunque el proyecto ya estuviera inactivo, que es justo lo que cierra este agujero.
     */
    @Test
    void anUpdateArrivingAfterADeletionDoesNotBringTheProjectBack() {
        projectRepository.upsertFromMasterData("mto-configuration", "42", "EP-42", "Tramo", true, 10L);
        entityManager.clear();
        assertEquals(1, projectRepository.deactivateFromMasterData("mto-configuration", "42", 11L));
        entityManager.clear();

        assertEquals(0, projectRepository.upsertFromMasterData(
                "mto-configuration", "42", "EP-42", "Tramo", true, 10L));
        entityManager.clear();

        assertFalse(projectRepository
                .findBySourceServiceAndSourceEntityId("mto-configuration", "42")
                .orElseThrow()
                .getActive());
    }

    /** Igual numero es el mismo cambio, no uno anterior: se aplica en lugar de descartarse. */
    @Test
    void aChangeWithTheSameSequenceNumberIsStillApplied() {
        projectRepository.upsertFromMasterData("mto-configuration", "42", "EP-42", "Tramo", true, 10L);
        entityManager.clear();

        assertEquals(1, projectRepository.upsertFromMasterData(
                "mto-configuration", "42", "EP-42", "Tramo revisado", true, 10L));
    }

    /**
     * Sin numero no se puede ordenar, asi que se aplica; pero la marca de agua anterior se conserva
     * en lugar de borrarse, o el siguiente evento viejo entraria.
     */
    @Test
    void aChangeWithoutSequenceNumberIsAppliedAndKeepsTheStoredWatermark() {
        projectRepository.upsertFromMasterData("mto-configuration", "42", "EP-42", "Tramo", true, 10L);
        entityManager.clear();

        assertEquals(1, projectRepository.upsertFromMasterData(
                "mto-configuration", "42", "EP-42", "Sin secuencia", true, null));
        entityManager.clear();

        Project project = projectRepository
                .findBySourceServiceAndSourceEntityId("mto-configuration", "42")
                .orElseThrow();
        assertEquals("Sin secuencia", project.getName());
        assertEquals(10L, project.getSourceSequenceNumber());
    }

    /**
     * Una clave se reclama una sola vez por operación y por quien la manda: la de otro cliente o la de
     * otra operación son otras claves. Lo creado se apunta una vez y no se sobrescribe.
     */
    @Test
    void anIdempotencyKeyIsClaimedOncePerOperationAndCaller() {
        UUID reservationId = UUID.randomUUID();

        assertEquals(1, idempotentRequestRepository.claim("RESERVATION", "key-1", "hash-1", "mto-maintenance-svc"));
        assertEquals(0, idempotentRequestRepository.claim("RESERVATION", "key-1", "hash-1", "mto-maintenance-svc"));
        assertEquals(1, idempotentRequestRepository.claim("RESERVATION", "key-1", "hash-1", "warehouse.operator"));
        assertEquals(1, idempotentRequestRepository.claim("OUTPUT", "key-1", "hash-1", "mto-maintenance-svc"));
        assertEquals(1, idempotentRequestRepository.recordResource("RESERVATION", "key-1", "mto-maintenance-svc", reservationId));
        assertEquals(0, idempotentRequestRepository.recordResource("RESERVATION", "key-1", "mto-maintenance-svc", UUID.randomUUID()));
        entityManager.clear();

        IdempotentRequest stored = idempotentRequestRepository
                .findByOperationAndCreatedByAndIdempotencyKey(IdempotentOperation.RESERVATION, "mto-maintenance-svc", "key-1")
                .orElseThrow();
        assertEquals("hash-1", stored.getRequestHash());
        assertEquals(reservationId, stored.getResourceId());
        assertNull(idempotentRequestRepository
                .findByOperationAndCreatedByAndIdempotencyKey(IdempotentOperation.OUTPUT, "mto-maintenance-svc", "key-1")
                .orElseThrow()
                .getResourceId());
    }

    /** La garantía es la restricción, no el {@code on conflict}: una inserción a pelo con la misma clave falla. */
    @Test
    void theIdempotentRequestTableKeepsASingleRowPerKey() {
        idempotentRequestRepository.claim("OUTPUT", "key-1", "hash-1", "mto-maintenance-svc");

        assertThrows(PersistenceException.class, () -> {
            entityManager.createNativeQuery("""
                    insert into idempotent_request (operation, idempotency_key, request_hash, created_by, updated_by)
                    values ('OUTPUT', 'key-1', 'hash-2', 'mto-maintenance-svc', 'mto-maintenance-svc')
                    """).executeUpdate();
            entityManager.flush();
        });
    }

    /**
     * Dos peticiones con la misma clave a la vez, con dos transacciones de verdad. La segunda no ve
     * la fila sin confirmar de la primera, pero espera en el índice único hasta que la primera
     * termina, y entonces no inserta nada y lee lo que la primera creó. Es lo que hace que un
     * reintento que llega mientras la petición original sigue en curso no escriba dos veces.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aSecondClaimOfTheSameKeyWaitsForTheFirstAndThenSeesWhatItCreated() throws Exception {
        String key = "concurrent-" + UUID.randomUUID();
        UUID created = UUID.randomUUID();
        TransactionStatus first = transactionManager.getTransaction(new DefaultTransactionDefinition());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            assertEquals(1, idempotentRequestRepository.claim("OUTPUT", key, "hash-1", "mto-maintenance-svc"));

            Future<Optional<UUID>> second = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                if (idempotentRequestRepository.claim("OUTPUT", key, "hash-1", "mto-maintenance-svc") == 1) {
                    return Optional.<UUID>empty();
                }
                return idempotentRequestRepository
                        .findByOperationAndCreatedByAndIdempotencyKey(IdempotentOperation.OUTPUT, "mto-maintenance-svc", key)
                        .map(IdempotentRequest::getResourceId);
            }));
            Thread.sleep(300);
            assertFalse(second.isDone(), "the second claim must wait while the first transaction is open");

            idempotentRequestRepository.recordResource("OUTPUT", key, "mto-maintenance-svc", created);
            transactionManager.commit(first);

            assertEquals(Optional.of(created), second.get(10, TimeUnit.SECONDS));
        } finally {
            if (!first.isCompleted()) {
                transactionManager.rollback(first);
            }
            executor.shutdownNow();
            deleteIdempotentRequests(key);
        }
    }

    /**
     * Si la escritura falla (stock dijo que no), su transacción revierte y la reclamación se va con
     * ella: el reintento con la misma clave se ejecuta como si fuera el primero.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aClaimRolledBackWithItsWriteLeavesTheKeyFreeForTheRetry() {
        String key = "rolled-back-" + UUID.randomUUID();
        UUID created = UUID.randomUUID();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        try {
            transaction.executeWithoutResult(status -> {
                assertEquals(1, idempotentRequestRepository.claim("RESERVATION", key, "hash-1", "mto-maintenance-svc"));
                status.setRollbackOnly();
            });

            transaction.executeWithoutResult(status -> {
                assertEquals(1, idempotentRequestRepository.claim("RESERVATION", key, "hash-1", "mto-maintenance-svc"));
                assertEquals(1, idempotentRequestRepository.recordResource("RESERVATION", key, "mto-maintenance-svc", created));
            });

            assertEquals(created, transaction.execute(status -> idempotentRequestRepository
                    .findByOperationAndCreatedByAndIdempotencyKey(IdempotentOperation.RESERVATION, "mto-maintenance-svc", key)
                    .orElseThrow()
                    .getResourceId()));
        } finally {
            deleteIdempotentRequests(key);
        }
    }

    /**
     * La V11 escribe la salida que le falta a cada reserva consumida antes de que consumir escribiera
     * el libro, y solo esa: la consumida con una salida de movimientos ya tiene la suya, y la activa y
     * la liberada no salen. Se ejecuta el script de verdad dos veces, porque Flyway lo corrió sobre las
     * tablas vacías al arrancar: la segunda pasada no duplica nada.
     */
    @Test
    void theBackfillWritesTheMissingOutputOfAConsumedReservationOnce() throws Exception {
        Material material = persist(material("MAT-V11", "Contact wire"));
        Warehouse warehouse = persist(warehouse("WH-V11"));
        Project project = persist(project("PRJ-V11"));
        Reservation withoutOutput = persist(reservation(material, warehouse, project, "4.000000", ReservationStatus.CONSUMED));
        Reservation withOutput = persist(reservation(material, warehouse, project, "2.000000", ReservationStatus.CONSUMED));
        StockMovement existing = movement(material, warehouse, project, StockMovementType.OUTPUT, "2.000000",
                Instant.parse("2026-08-01T12:00:00Z"));
        existing.setReservation(withOutput);
        persist(existing);
        Reservation active = persist(reservation(material, warehouse, project, "1.000000", ReservationStatus.ACTIVE));
        Reservation released = persist(reservation(material, warehouse, project, "3.000000", ReservationStatus.RELEASED));
        flushAndClear();
        // Quien la consumió va con SQL y no por la entidad: AuditingEntityListener pisaría updated_by con
        // el actor del test ("system") si un contexto con auditoría arrancó antes en la misma JVM, porque
        // el aspecto @Configurable que lo configura es uno para toda ella.
        entityManager.createNativeQuery("update reservation set updated_by = 'almacen.responsable' where id = :id")
                .setParameter("id", withoutOutput.getId())
                .executeUpdate();
        String backfill = new ClassPathResource("db/migration/V11__backfill_outputs_of_consumed_reservations.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        entityManager.unwrap(Session.class).doWork(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(backfill);
                statement.executeUpdate(backfill);
            }
        });
        entityManager.clear();

        List<StockMovement> backfilled = outputsOf(withoutOutput);
        assertEquals(1, backfilled.size());
        StockMovement output = backfilled.getFirst();
        assertEquals(0, new BigDecimal("4").compareTo(output.getQuantity()));
        assertEquals(material.getId(), output.getMaterial().getId());
        assertEquals(warehouse.getId(), output.getWarehouse().getId());
        assertEquals(project.getId(), output.getProject().getId());
        assertEquals(Instant.parse("2026-08-01T12:00:00Z"), output.getOccurredAt());
        assertEquals("almacen.responsable", output.getCreatedBy());
        assertTrue(output.getNotes().contains("V11"));
        assertEquals(List.of(existing.getId()), outputsOf(withOutput).stream().map(StockMovement::getId).toList());
        assertTrue(outputsOf(active).isEmpty());
        assertTrue(outputsOf(released).isEmpty());
    }

    private List<StockMovement> outputsOf(Reservation reservation) {
        return entityManager.createQuery("""
                        select m from StockMovement m
                         where m.reservation.id = :reservationId and m.type = :type
                        """, StockMovement.class)
                .setParameter("reservationId", reservation.getId())
                .setParameter("type", StockMovementType.OUTPUT)
                .getResultList();
    }

    private java.util.List<String> lowStockCodes(org.springframework.data.jpa.domain.Specification<Material> specification) {
        return materialRepository.findAll(specification).stream()
                .map(Material::getCode)
                .sorted()
                .toList();
    }

    private <T> T persist(T entity) {
        if (entity instanceof AuditableEntity auditableEntity) {
            audit(auditableEntity);
        }
        entityManager.persist(entity);
        return entity;
    }

    /** Lo que confirmaron los tests con transacciones de verdad no se queda en la base que comparten todas las clases. */
    /**
     * El bloqueo del material es lo que serializa las escrituras de saldo de un mismo material
     * ({@code InventoryBalanceServiceImpl}): mientras una transaccion lo tiene, otra que lo pida no
     * lo consigue. Se comprueba con {@code for update nowait} desde otra transaccion, que falla en
     * vez de esperar, y que vuelve a conseguirse en cuanto la primera termina.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void findByIdForUpdateLocksTheMaterialRowUntilTheTransactionEnds() throws Exception {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        UUID materialId = template.execute(status -> {
            Material material = material("MAT-LOCK-" + UUID.randomUUID().toString().substring(0, 8), "Locked material");
            audit(material);
            entityManager.persist(material);
            return material.getId();
        });
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> holder = executor.submit(() -> template.execute(status -> {
                boolean found = materialRepository.findByIdForUpdate(materialId).isPresent();
                locked.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return found;
            }));
            assertTrue(locked.await(10, TimeUnit.SECONDS));

            assertThrows(RuntimeException.class, () -> template.executeWithoutResult(status -> lockNoWait(materialId)),
                    "the row is locked while the first transaction is open");

            release.countDown();
            assertTrue(holder.get(10, TimeUnit.SECONDS));
            template.executeWithoutResult(status -> assertEquals(1, lockNoWait(materialId).size()));
        } finally {
            release.countDown();
            executor.shutdownNow();
            template.executeWithoutResult(status -> entityManager
                    .createNativeQuery("delete from material where id = :id").setParameter("id", materialId).executeUpdate());
        }
    }

    private List<?> lockNoWait(UUID materialId) {
        return entityManager.createNativeQuery("select id from material where id = :id for update nowait")
                .setParameter("id", materialId).getResultList();
    }

    private void deleteIdempotentRequests(String key) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> entityManager
                .createNativeQuery("delete from idempotent_request where idempotency_key = :key")
                .setParameter("key", key)
                .executeUpdate());
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

    private static InventoryBalance balance(Material material,
                                            Warehouse warehouse,
                                            String physicalQuantity,
                                            String reservedQuantity) {
        BigDecimal physical = new BigDecimal(physicalQuantity);
        BigDecimal reserved = new BigDecimal(reservedQuantity);
        return InventoryBalance.builder()
                .material(material)
                .warehouse(warehouse)
                .physicalQuantity(physical)
                .reservedQuantity(reserved)
                .availableQuantity(physical.subtract(reserved))
                .build();
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