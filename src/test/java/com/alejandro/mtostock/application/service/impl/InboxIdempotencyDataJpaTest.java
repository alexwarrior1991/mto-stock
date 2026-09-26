package com.alejandro.mtostock.application.service.impl;

import com.alejandro.mtostock.application.dto.messaging.InboxMessageCommand;
import com.alejandro.mtostock.application.dto.messaging.MasterDataChangedEvent;
import com.alejandro.mtostock.application.dto.messaging.MasterDataChangedMessage;
import com.alejandro.mtostock.application.dto.messaging.MasterDataEntityNames;
import com.alejandro.mtostock.application.dto.messaging.MasterDataEventContext;
import com.alejandro.mtostock.application.dto.messaging.MasterDataOperation;
import com.alejandro.mtostock.application.dto.messaging.InboxProcessingResult;
import com.alejandro.mtostock.application.dto.reservation.ReservationRequest;
import com.alejandro.mtostock.application.dto.reservation.ReservationResponse;
import com.alejandro.mtostock.application.dto.stock.StockMovementOutputRequest;
import com.alejandro.mtostock.application.dto.stock.StockMovementResponse;
import com.alejandro.mtostock.application.exception.IdempotencyKeyConflictException;
import com.alejandro.mtostock.application.mapper.AuditableMapper;
import com.alejandro.mtostock.application.mapper.AuditableMapperImpl;
import com.alejandro.mtostock.application.mapper.MaterialMapperImpl;
import com.alejandro.mtostock.application.mapper.ProjectMapperImpl;
import com.alejandro.mtostock.application.mapper.ReservationMapper;
import com.alejandro.mtostock.application.mapper.ReservationMapperImpl;
import com.alejandro.mtostock.application.mapper.ReservationStatusMapperImpl;
import com.alejandro.mtostock.application.mapper.StockMovementMapper;
import com.alejandro.mtostock.application.mapper.StockMovementMapperImpl;
import com.alejandro.mtostock.application.mapper.StockMovementTypeMapperImpl;
import com.alejandro.mtostock.application.mapper.SupplierMapperImpl;
import com.alejandro.mtostock.application.mapper.WarehouseMapperImpl;
import com.alejandro.mtostock.application.service.EntityAuditService;
import com.alejandro.mtostock.application.service.InboxMessageService;
import com.alejandro.mtostock.application.service.MasterDataEventHandler;
import com.alejandro.mtostock.configuration.JpaAuditingConfiguration;
import com.alejandro.mtostock.configuration.cache.CacheInvalidator;
import com.alejandro.mtostock.infrastructure.persistence.entity.EntityReferenceFactory;
import com.alejandro.mtostock.infrastructure.persistence.entity.InboxMessage;
import com.alejandro.mtostock.infrastructure.persistence.entity.Project;
import com.alejandro.mtostock.infrastructure.persistence.entity.InboxMessageStatus;
import com.alejandro.mtostock.infrastructure.persistence.entity.Material;
import com.alejandro.mtostock.infrastructure.persistence.entity.StockMovementType;
import com.alejandro.mtostock.infrastructure.persistence.entity.Warehouse;
import com.alejandro.mtostock.infrastructure.persistence.repository.AssemblyRepository;
import com.alejandro.mtostock.infrastructure.persistence.repository.IdempotentRequestRepository;
import com.alejandro.mtostock.infrastructure.persistence.repository.InboxMessageRepository;
import com.alejandro.mtostock.infrastructure.persistence.repository.InventoryBalanceRepository;
import com.alejandro.mtostock.infrastructure.persistence.repository.MaterialRepository;
import com.alejandro.mtostock.infrastructure.persistence.repository.ProjectRepository;
import com.alejandro.mtostock.infrastructure.persistence.repository.ReservationRepository;
import com.alejandro.mtostock.infrastructure.persistence.repository.StockMovementRepository;
import com.alejandro.mtostock.infrastructure.persistence.repository.SupplierRepository;
import com.alejandro.mtostock.infrastructure.persistence.repository.WarehouseRepository;
import com.alejandro.mtostock.infrastructure.persistence.specification.ReservationSpecification;
import com.alejandro.mtostock.infrastructure.persistence.specification.StockMovementSpecification;
import com.alejandro.mtostock.support.PostgreSQLTestContainer;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.Mockito.mock;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * La idempotencia de punta a punta contra PostgreSQL, en sus dos formas: el inbox, que aplica una
 * sola vez cada evento de datos maestros, y las escrituras con {@code Idempotency-Key}, que dejan
 * una sola reserva o una sola salida por clave.
 *
 * <p>Está aquí, en el paquete del servicio, y no entre los tests de repositorio, porque lo que se
 * prueba es el servicio completo sobre SQL real. Con un doble de repositorio no probaría nada: la
 * idempotencia no la decide el código, la decide la restricción única de la tabla, y un doble
 * devuelve lo que se le diga. Los servicios se montan a mano, con los repositorios de verdad y, para
 * las escrituras, los mappers generados; {@link JpaAuditingConfiguration} rellena las columnas de
 * auditoría de lo que guardan.</p>
 *
 * <p>Lo que no cabe aquí es la carrera entre dos peticiones <b>simultáneas</b>: haría falta mantener
 * dos transacciones abiertas a la vez sobre dos conexiones, y un test transaccional tiene una. Esa
 * espera la aporta PostgreSQL —bloqueando a la segunda en el índice único mientras la primera no ha
 * confirmado, o en la fila si ya estaba confirmada—; lo que se comprueba aquí es lo que ve la segunda
 * cuando la primera ya terminó, que es el resultado de esa espera. La espera misma, con dos
 * transacciones de verdad, está para las claves de las escrituras en
 * {@code InventoryRepositoryDataJpaTest}.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(JpaAuditingConfiguration.class)
class InboxIdempotencyDataJpaTest extends PostgreSQLTestContainer {

    private static final String MESSAGE_ID = "0f8b1f4c-3f6a-4a6d-9a2a-1c9f5f6f2b10";
    private static final String SOURCE_SERVICE = "mto-configuration";
    private static final String CALLER = "mto-maintenance-svc";
    private static final String PAYLOAD = """
            {"operationId":"0f8b1f4c-3f6a-4a6d-9a2a-1c9f5f6f2b10","eventType":"MASTER_DATA_STATION_UPDATED"}""";

    @DynamicPropertySource
    static void postgreSQLProperties(DynamicPropertyRegistry registry) {
        registerPostgreSQLProperties(registry);
    }

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private InboxMessageRepository inboxMessageRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private MaterialRepository materialRepository;

    @Autowired
    private WarehouseRepository warehouseRepository;

    @Autowired
    private SupplierRepository supplierRepository;

    @Autowired
    private AssemblyRepository assemblyRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private StockMovementRepository stockMovementRepository;

    @Autowired
    private InventoryBalanceRepository inventoryBalanceRepository;

    @Autowired
    private IdempotentRequestRepository idempotentRequestRepository;

    @Test
    void theWorkRunsOnceAcrossTwoDeliveriesOfTheSameMessage() {
        InboxMessageService service = new InboxMessageServiceImpl(inboxMessageRepository);
        AtomicInteger executions = new AtomicInteger();

        InboxProcessingResult first = service.process(command(), executions::incrementAndGet);
        entityManager.clear();
        InboxProcessingResult second = service.process(command(), executions::incrementAndGet);

        assertEquals(InboxProcessingResult.PROCESSED, first);
        assertEquals(InboxProcessingResult.DUPLICATE_SKIPPED, second);
        assertEquals(1, executions.get());

        InboxMessage stored = reload();
        assertEquals(InboxMessageStatus.PROCESSED, stored.getStatus());
        // El duplicado no gasta intento: no llega a reclamar nada.
        assertEquals(1, stored.getProcessingAttempts());
        assertEquals(1, inboxMessageRepository.count());
    }

    /**
     * Ciclo completo de fallo y reintento. El estado fallido lo escribe {@code recordFailure} en su
     * propia transacción porque la del intento revierte y se lo llevaría por delante.
     */
    @Test
    void aFailedMessageIsRecordedAndCanBeRetriedUntilItSucceeds() {
        InboxMessageService service = new InboxMessageServiceImpl(inboxMessageRepository);
        RuntimeException failure = new IllegalStateException("database is down");

        assertThrows(IllegalStateException.class, () -> service.process(command(), () -> {
            throw failure;
        }));
        service.recordFailure(command(), failure);
        entityManager.clear();

        InboxMessage afterFailure = reload();
        assertEquals(InboxMessageStatus.FAILED, afterFailure.getStatus());
        assertTrue(afterFailure.getFailureReason().contains("database is down"));

        AtomicInteger executions = new AtomicInteger();
        InboxProcessingResult retry = service.process(command(), executions::incrementAndGet);
        entityManager.clear();

        assertEquals(InboxProcessingResult.PROCESSED, retry);
        assertEquals(1, executions.get());
        assertEquals(InboxMessageStatus.PROCESSED, reload().getStatus());
    }

    /**
     * La cadena entera sobre la base de datos real: inbox, despachador y manejador de paquetes de
     * ejecucion. Dos entregas del mismo evento dejan un solo proyecto, que es el resultado que le
     * importa a alguien, no el numero de veces que se llamo a un metodo.
     */
    @Test
    void twoDeliveriesOfAnExecutionPackageLeaveASingleSynchronizedProject() {
        InboxMessageService inbox = new InboxMessageServiceImpl(inboxMessageRepository);
        MasterDataEventHandler dispatcher = new DispatchingMasterDataEventHandler(
                List.of(new ExecutionPackageMasterDataHandler(projectRepository, mock(CacheInvalidator.class))));
        MasterDataChangedMessage message = executionPackageCreated();

        inbox.process(command(), () -> dispatcher.handle(message, new MasterDataEventContext(10L)));
        entityManager.flush();
        entityManager.clear();
        inbox.process(command(), () -> dispatcher.handle(message, new MasterDataEventContext(10L)));
        entityManager.flush();
        entityManager.clear();

        Project project = projectRepository
                .findBySourceServiceAndSourceEntityId("mto-configuration", "42")
                .orElseThrow();
        assertEquals("EP-42", project.getCode());
        assertEquals("Tramo Sants-Sagrera", project.getName());
        assertTrue(project.getActive());
        assertEquals(1, projectRepository.count());
    }

    /** Una entidad sin manejador no rompe nada y no deja rastro en las tablas de negocio. */
    @Test
    void aCantileverChangeIsRecordedInTheInboxAndTouchesNoProject() {
        InboxMessageService inbox = new InboxMessageServiceImpl(inboxMessageRepository);
        MasterDataEventHandler dispatcher = new DispatchingMasterDataEventHandler(
                List.of(new ExecutionPackageMasterDataHandler(projectRepository, mock(CacheInvalidator.class))));
        MasterDataChangedMessage cantilever = new MasterDataChangedMessage(
                UUID.fromString(MESSAGE_ID), "cantilever-7", "mto-configuration", Instant.now(),
                "MASTER_DATA_CANTILEVER_UPDATED",
                new MasterDataChangedEvent(MasterDataEntityNames.CANTILEVER, "7",
                        MasterDataOperation.UPDATED, Map.of("stagger", "0.20")),
                "hash");

        InboxProcessingResult result = inbox.process(command(), () -> dispatcher.handle(cantilever, new MasterDataEventContext(10L)));
        entityManager.flush();
        entityManager.clear();

        assertEquals(InboxProcessingResult.PROCESSED, result);
        assertEquals(0, projectRepository.count());
        assertEquals(InboxMessageStatus.PROCESSED, reload().getStatus());
    }

    /**
     * La reserva de punta a punta: servicio, motor, saldo y tabla de claves sobre PostgreSQL. La
     * primera reserva se lleva todo el disponible, así que un reintento que volviera a validar
     * existencias respondería 409 aunque la reserva ya estuviera hecha: con la clave no valida nada,
     * no reserva otra vez y devuelve la misma reserva.
     */
    @Test
    void aReservationRepeatedWithTheSameKeyIsMadeOnceEvenWithNothingLeftToReserve() {
        Stock stock = stock("4.000000");
        ReservationServiceImpl reservations = reservationService();
        ReservationRequest request = new ReservationRequest(stock.material().getId(), stock.warehouse().getId(),
                stock.project().getId(), new BigDecimal("4.000000"), null);

        ReservationResponse first = reservations.create(request, "mto-maintenance:line-1:reserve");
        entityManager.flush();
        entityManager.clear();
        ReservationResponse retry = reservations.create(request, "mto-maintenance:line-1:reserve");

        assertEquals(first.id(), retry.id());
        assertEquals(reservations.findById(first.id()), retry);
        assertEquals(1, reservationRepository.findAll(ReservationSpecification.materialIdEquals(stock.material().getId())).size());
        assertEquals(0, new BigDecimal("4").compareTo(inventoryBalanceRepository.calculateReservedQuantity(
                stock.material().getId(), stock.warehouse().getId(), BigDecimal.ZERO)));
    }

    /** Lo mismo con la salida, que es la que descuenta el físico: dos peticiones, un apunte y un descuento. */
    @Test
    void anOutputRepeatedWithTheSameKeyTakesTheMaterialOutOnce() {
        Stock stock = stock("3.000000");
        StockMovementServiceImpl movements = stockMovementService();
        StockMovementOutputRequest request = new StockMovementOutputRequest(stock.material().getId(), stock.warehouse().getId(),
                stock.project().getId(), null, new BigDecimal("3.000000"), null, "MO-000001", "Maintenance order MO-000001");

        StockMovementResponse first = movements.registerOutput(request, "mto-maintenance:line-1:output");
        entityManager.flush();
        entityManager.clear();
        StockMovementResponse retry = movements.registerOutput(request, "mto-maintenance:line-1:output");

        assertEquals(first.id(), retry.id());
        assertEquals(movements.findById(first.id()), retry);
        assertEquals(1, stockMovementRepository.findAll(StockMovementSpecification.materialIdEquals(stock.material().getId())
                .and(StockMovementSpecification.typeEquals(StockMovementType.OUTPUT))).size());
        assertEquals(0, BigDecimal.ZERO.compareTo(inventoryBalanceRepository.calculatePhysicalQuantity(
                stock.material().getId(), stock.warehouse().getId(), BigDecimal.ZERO)));
    }

    /** La misma clave con otro cuerpo no escribe nada: ni otra reserva ni otro descuento del disponible. */
    @Test
    void aKeyReusedWithAnotherBodyIsRejectedAndWritesNothing() {
        Stock stock = stock("10.000000");
        ReservationServiceImpl reservations = reservationService();
        UUID materialId = stock.material().getId();
        UUID warehouseId = stock.warehouse().getId();
        reservations.create(new ReservationRequest(materialId, warehouseId, stock.project().getId(), new BigDecimal("4.000000"), null), "key-1");
        entityManager.flush();
        entityManager.clear();

        ReservationRequest other = new ReservationRequest(materialId, warehouseId, stock.project().getId(), new BigDecimal("5.000000"), null);
        assertThrows(IdempotencyKeyConflictException.class, () -> reservations.create(other, "key-1"));

        assertEquals(1, reservationRepository.findAll(ReservationSpecification.materialIdEquals(materialId)).size());
        assertEquals(0, new BigDecimal("4").compareTo(inventoryBalanceRepository.calculateReservedQuantity(materialId, warehouseId, BigDecimal.ZERO)));
    }

    /**
     * La fecha no cuenta: un reintento que trae la hora de su intento es la misma reserva, y la que
     * queda es la del primero. Sin esto el cliente tenía que dejar la fecha fuera para poder reintentar.
     */
    @Test
    void aRetryWithAnotherDateIsTheSameReservationAndKeepsTheFirstDate() {
        Stock stock = stock("4.000000");
        ReservationServiceImpl reservations = reservationService();
        Instant firstAttempt = Instant.parse("2026-09-01T08:00:00Z");

        ReservationResponse first = reservations.create(new ReservationRequest(stock.material().getId(), stock.warehouse().getId(),
                stock.project().getId(), new BigDecimal("4.000000"), firstAttempt), "mto-maintenance:line-1:reserve");
        entityManager.flush();
        entityManager.clear();
        ReservationResponse retry = reservations.create(new ReservationRequest(stock.material().getId(), stock.warehouse().getId(),
                stock.project().getId(), new BigDecimal("4.000000"), firstAttempt.plusSeconds(90)), "mto-maintenance:line-1:reserve");

        assertEquals(first.id(), retry.id());
        assertEquals(firstAttempt, retry.reservedAt());
        assertEquals(1, reservationRepository.findAll(ReservationSpecification.materialIdEquals(stock.material().getId())).size());
    }

    /**
     * La purga olvida las claves usadas antes del corte, y solo esas. Lo que crearon sigue donde
     * estaba; lo único que se pierde es reconocer el reintento, que pasa a ser una petición nueva: es
     * el contrato, y por eso el plazo tiene que sobrar frente a lo que tarda un cliente en reintentar.
     */
    @Test
    void thePurgeForgetsTheExpiredKeysAndARetryWithOneIsANewRequest() {
        Stock stock = stock("12.000000");
        ReservationServiceImpl reservations = reservationService();
        ReservationRequest request = new ReservationRequest(stock.material().getId(), stock.warehouse().getId(),
                stock.project().getId(), new BigDecimal("4.000000"), null);
        ReservationResponse expired = reservations.create(request, "expired-key");
        ReservationResponse recent = reservations.create(request, "recent-key");
        entityManager.flush();
        // PostgreSQL pone la misma hora a todo lo escrito en una transacción: la clave vieja se envejece a mano.
        entityManager.createNativeQuery("update idempotent_request set created_at = now() - interval '31 days' "
                + "where idempotency_key = 'expired-key'").executeUpdate();
        entityManager.clear();

        assertEquals(1, idempotentRequestService().purgeClaimedBefore(Instant.now().minus(Duration.ofDays(30))));

        assertEquals(recent.id(), reservations.create(request, "recent-key").id());
        ReservationResponse afterExpiry = reservations.create(request, "expired-key");
        assertNotEquals(expired.id(), afterExpiry.id());
        assertTrue(reservationRepository.findById(expired.id()).isPresent());
        assertEquals(3, reservationRepository.findAll(ReservationSpecification.materialIdEquals(stock.material().getId())).size());
    }

    /** Material, almacén y proyecto dados de alta, con {@code physical} en el almacén y nada reservado. */
    private Stock stock(String physical) {
        Material material = persist(Material.builder().code("MAT-IDEM").name("Contact wire")
                .unitOfMeasure("m").minimumStockLevel(BigDecimal.ZERO).build());
        Warehouse warehouse = persist(Warehouse.builder().code("WH-IDEM").name("Warehouse IDEM").build());
        Project project = persist(Project.builder().code("PRJ-IDEM").name("Project IDEM").build());
        entityManager.flush();
        inventoryBalanceRepository.insertZeroBalanceIfMissing(material.getId(), warehouse.getId(), CALLER);
        inventoryBalanceRepository.increasePhysical(material.getId(), warehouse.getId(), new BigDecimal(physical), CALLER);
        entityManager.clear();
        return new Stock(material, warehouse, project);
    }

    private <T> T persist(T entity) {
        entityManager.persist(entity);
        return entity;
    }

    /** Los servicios de verdad, montados a mano como el resto de la clase, con los mappers generados. */
    private ReservationServiceImpl reservationService() {
        return new ReservationServiceImpl(reservationRepository, reservationMapper(), mock(EntityAuditService.class),
                reservationEngine(), new EntityReferenceFactory(), idempotentRequestService());
    }

    private StockMovementServiceImpl stockMovementService() {
        AuditableMapper auditable = new AuditableMapperImpl();
        EntityReferenceFactory references = new EntityReferenceFactory();
        StockMovementMapper mapper = new StockMovementMapperImpl(auditable, new MaterialMapperImpl(auditable, references),
                new WarehouseMapperImpl(auditable, references), new SupplierMapperImpl(auditable, references),
                new ProjectMapperImpl(auditable, references), reservationMapper(), new StockMovementTypeMapperImpl(), references);
        return new StockMovementServiceImpl(stockMovementRepository, materialRepository, warehouseRepository, supplierRepository,
                projectRepository, reservationRepository, mapper, balanceService(), validationService(), reservationEngine(),
                idempotentRequestService());
    }

    private ReservationEngineImpl reservationEngine() {
        return new ReservationEngineImpl(reservationRepository, materialRepository, warehouseRepository, projectRepository,
                balanceService(), validationService());
    }

    private InventoryBalanceServiceImpl balanceService() {
        return new InventoryBalanceServiceImpl(inventoryBalanceRepository, () -> Optional.of(CALLER));
    }

    private InventoryValidationServiceImpl validationService() {
        return new InventoryValidationServiceImpl(materialRepository, assemblyRepository, warehouseRepository,
                supplierRepository, projectRepository);
    }

    private IdempotentRequestServiceImpl idempotentRequestService() {
        return new IdempotentRequestServiceImpl(idempotentRequestRepository, () -> Optional.of(CALLER));
    }

    private static ReservationMapper reservationMapper() {
        AuditableMapper auditable = new AuditableMapperImpl();
        EntityReferenceFactory references = new EntityReferenceFactory();
        return new ReservationMapperImpl(auditable, new MaterialMapperImpl(auditable, references),
                new WarehouseMapperImpl(auditable, references), new ProjectMapperImpl(auditable, references),
                new ReservationStatusMapperImpl(), references);
    }

    private record Stock(Material material, Warehouse warehouse, Project project) {
    }

    private static MasterDataChangedMessage executionPackageCreated() {
        return new MasterDataChangedMessage(
                UUID.fromString(MESSAGE_ID),
                "execution-package-42",
                "mto-configuration",
                Instant.parse("2026-09-01T10:15:30Z"),
                "MASTER_DATA_EXECUTION_PACKAGE_CREATED",
                new MasterDataChangedEvent(MasterDataEntityNames.EXECUTION_PACKAGE, "42",
                        MasterDataOperation.CREATED,
                        Map.of("id", 42, "name", "Tramo Sants-Sagrera", "enabled", true,
                                "length", 12_500L, "startDate", "2026-01-15")),
                "hash");
    }

    private static InboxMessageCommand command() {
        return new InboxMessageCommand(MESSAGE_ID, SOURCE_SERVICE, "MASTER_DATA_STATION_UPDATED", "station",
                "42", "mto.master-data.exchange", "mto.master-data.station.updated",
                "mto.stock.master-data.queue", "9f2c1b0d", PAYLOAD, 10L);
    }

    private InboxMessage reload() {
        entityManager.clear();
        return inboxMessageRepository.findByMessageIdAndSourceService(MESSAGE_ID, SOURCE_SERVICE).orElseThrow();
    }
}
