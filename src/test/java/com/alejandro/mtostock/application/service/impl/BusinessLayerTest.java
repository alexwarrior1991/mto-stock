package com.alejandro.mtostock.application.service.impl;

import com.alejandro.mtostock.application.dto.assembly.AssemblyAvailabilityResponse;
import com.alejandro.mtostock.application.dto.assembly.AssemblySummaryResponse;
import com.alejandro.mtostock.application.dto.material.MaterialSummaryResponse;
import com.alejandro.mtostock.application.dto.stock.StockMovementEntryRequest;
import com.alejandro.mtostock.application.dto.stock.StockMovementOutputRequest;
import com.alejandro.mtostock.application.dto.stock.StockMovementTransferRequest;
import com.alejandro.mtostock.application.dto.warehouse.WarehouseSummaryResponse;
import com.alejandro.mtostock.application.exception.AssemblyException;
import com.alejandro.mtostock.application.exception.InsufficientStockException;
import com.alejandro.mtostock.application.exception.ReservationException;
import com.alejandro.mtostock.application.exception.WarehouseException;
import com.alejandro.mtostock.application.mapper.AssemblyMapper;
import com.alejandro.mtostock.application.mapper.MaterialMapper;
import com.alejandro.mtostock.application.mapper.StockMovementMapper;
import com.alejandro.mtostock.application.mapper.WarehouseMapper;
import com.alejandro.mtostock.application.service.InventoryBalanceService;
import com.alejandro.mtostock.application.service.InventoryValidationService;
import com.alejandro.mtostock.application.service.ReservationEngine;
import com.alejandro.mtostock.application.service.StockCalculationService;
import com.alejandro.mtostock.infrastructure.persistence.entity.Assembly;
import com.alejandro.mtostock.infrastructure.persistence.entity.AssemblyComponent;
import com.alejandro.mtostock.infrastructure.persistence.entity.Material;
import com.alejandro.mtostock.infrastructure.persistence.entity.Project;
import com.alejandro.mtostock.infrastructure.persistence.entity.Reservation;
import com.alejandro.mtostock.infrastructure.persistence.entity.ReservationStatus;
import com.alejandro.mtostock.infrastructure.persistence.entity.StockMovement;
import com.alejandro.mtostock.infrastructure.persistence.entity.StockMovementType;
import com.alejandro.mtostock.infrastructure.persistence.entity.Warehouse;
import com.alejandro.mtostock.infrastructure.persistence.repository.AssemblyRepository;
import com.alejandro.mtostock.infrastructure.persistence.repository.InventoryBalanceRepository;
import com.alejandro.mtostock.infrastructure.persistence.repository.MaterialRepository;
import com.alejandro.mtostock.infrastructure.persistence.repository.ProjectRepository;
import com.alejandro.mtostock.infrastructure.persistence.repository.ReservationRepository;
import com.alejandro.mtostock.infrastructure.persistence.repository.StockMovementRepository;
import com.alejandro.mtostock.infrastructure.persistence.repository.SupplierRepository;
import com.alejandro.mtostock.infrastructure.persistence.repository.WarehouseRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.AuditorAware;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BusinessLayerTest {

    private static final Pattern REST_CONTROLLER_ANNOTATION = Pattern.compile("@RestController(?!Advice)\\b");

    @Test
    void stockCalculationReadsCurrentBalancesFromInventoryBalanceAndKeepsHistoricalFromMovements() {
        InventoryBalanceRepository inventoryBalanceRepository = mock(InventoryBalanceRepository.class);
        StockMovementRepository stockMovementRepository = mock(StockMovementRepository.class);
        UUID materialId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        when(inventoryBalanceRepository.calculatePhysicalQuantity(materialId, warehouseId, BigDecimal.ZERO))
                .thenReturn(new BigDecimal("8.000000"));
        when(inventoryBalanceRepository.calculateReservedQuantity(materialId, warehouseId, BigDecimal.ZERO))
                .thenReturn(new BigDecimal("3.000000"));
        when(inventoryBalanceRepository.calculateAvailableQuantity(materialId, warehouseId, BigDecimal.ZERO))
                .thenReturn(new BigDecimal("5.000000"));
        when(stockMovementRepository.calculateSignedQuantity(eq(materialId), eq(warehouseId), isNull(), anyCollection(), eq(BigDecimal.ZERO)))
                .thenReturn(new BigDecimal("8.000000"));
        StockCalculationServiceImpl service = new StockCalculationServiceImpl(
                inventoryBalanceRepository,
                stockMovementRepository,
                mock(MaterialRepository.class),
                mock(WarehouseRepository.class),
                mock(MaterialMapper.class),
                mock(WarehouseMapper.class)
        );

        assertEquals(new BigDecimal("8.000000"), service.calculatePhysicalStock(materialId, warehouseId));
        assertEquals(new BigDecimal("3.000000"), service.calculateReservedStock(materialId, warehouseId));
        assertEquals(new BigDecimal("5.000000"), service.calculateAvailableStock(materialId, warehouseId));
        assertEquals(new BigDecimal("8.000000"), service.calculateHistoricalStock(materialId, warehouseId, null));
        verify(stockMovementRepository).calculateSignedQuantity(eq(materialId), eq(warehouseId), isNull(), anyCollection(), eq(BigDecimal.ZERO));
    }

    @Test
    void bomCalculationFindsLimitingComponentFromAvailableComponentStock() {
        AssemblyRepository assemblyRepository = mock(AssemblyRepository.class);
        WarehouseRepository warehouseRepository = mock(WarehouseRepository.class);
        StockCalculationService stockCalculationService = mock(StockCalculationService.class);
        InventoryValidationService validationService = mock(InventoryValidationService.class);
        AssemblyMapper assemblyMapper = mock(AssemblyMapper.class);
        MaterialMapper materialMapper = mock(MaterialMapper.class);
        WarehouseMapper warehouseMapper = mock(WarehouseMapper.class);
        Material firstMaterial = material("MAT-001");
        Material secondMaterial = material("MAT-002");
        Warehouse warehouse = warehouse("WH-001");
        Assembly assembly = Assembly.builder().code("ASM-001").name("Section").build();
        setId(assembly, UUID.randomUUID());
        assembly.addComponent(component(firstMaterial, "2.000000"));
        assembly.addComponent(component(secondMaterial, "5.000000"));
        when(assemblyRepository.findWithComponentsById(assembly.getId())).thenReturn(Optional.of(assembly));
        when(warehouseRepository.findById(warehouse.getId())).thenReturn(Optional.of(warehouse));
        when(stockCalculationService.calculatePhysicalStock(firstMaterial.getId(), warehouse.getId())).thenReturn(new BigDecimal("10.000000"));
        when(stockCalculationService.calculateReservedStock(firstMaterial.getId(), warehouse.getId())).thenReturn(BigDecimal.ZERO);
        when(stockCalculationService.calculatePhysicalStock(secondMaterial.getId(), warehouse.getId())).thenReturn(new BigDecimal("15.000000"));
        when(stockCalculationService.calculateReservedStock(secondMaterial.getId(), warehouse.getId())).thenReturn(new BigDecimal("3.000000"));
        when(assemblyMapper.toSummaryResponse(assembly)).thenReturn(new AssemblySummaryResponse(assembly.getId(), assembly.getCode(), assembly.getName(), assembly.getActive()));
        when(warehouseMapper.toSummaryResponse(warehouse)).thenReturn(new WarehouseSummaryResponse(warehouse.getId(), warehouse.getCode(), warehouse.getName(), warehouse.getActive()));
        when(materialMapper.toSummaryResponse(firstMaterial)).thenReturn(materialSummary(firstMaterial));
        when(materialMapper.toSummaryResponse(secondMaterial)).thenReturn(materialSummary(secondMaterial));
        BOMCalculationServiceImpl service = new BOMCalculationServiceImpl(
                assemblyRepository,
                warehouseRepository,
                stockCalculationService,
                validationService,
                assemblyMapper,
                materialMapper,
                warehouseMapper
        );

        AssemblyAvailabilityResponse response = service.calculateAvailability(assembly.getId(), warehouse.getId());

        assertEquals(0, new BigDecimal("2.000000").compareTo(response.availableQuantity()));
        assertTrue(response.components().stream().anyMatch(component -> component.material().code().equals("MAT-002") && component.limitingComponent()));
        assertFalse(response.components().stream().anyMatch(component -> component.material().code().equals("MAT-001") && component.limitingComponent()));
    }

    @Test
    void reservationEngineConsumesActiveReservationWithConsumedStatus() {
        ReservationRepository reservationRepository = mock(ReservationRepository.class);
        InventoryBalanceService inventoryBalanceService = mock(InventoryBalanceService.class);
        InventoryValidationService validationService = mock(InventoryValidationService.class);
        Reservation reservation = reservation();
        when(reservationRepository.findById(reservation.getId())).thenReturn(Optional.of(reservation));
        ReservationEngineImpl service = new ReservationEngineImpl(
                reservationRepository,
                mock(MaterialRepository.class),
                mock(WarehouseRepository.class),
                mock(ProjectRepository.class),
                inventoryBalanceService,
                validationService
        );

        Reservation consumedReservation = service.consume(reservation.getId());

        assertEquals(ReservationStatus.CONSUMED, consumedReservation.getStatus());
        assertNotNull(consumedReservation.getReleasedAt());
        verify(validationService).validateReservationCanChange(reservation);
        verify(inventoryBalanceService).consumeReserved(reservation.getMaterial().getId(), reservation.getWarehouse().getId(), reservation.getQuantity());
    }

    @Test
    void reservationEngineReleaseFreesReservedBalanceWithoutMovement() {
        ReservationRepository reservationRepository = mock(ReservationRepository.class);
        InventoryBalanceService inventoryBalanceService = mock(InventoryBalanceService.class);
        InventoryValidationService validationService = mock(InventoryValidationService.class);
        Reservation reservation = reservation();
        when(reservationRepository.findById(reservation.getId())).thenReturn(Optional.of(reservation));
        ReservationEngineImpl service = new ReservationEngineImpl(
                reservationRepository,
                mock(MaterialRepository.class),
                mock(WarehouseRepository.class),
                mock(ProjectRepository.class),
                inventoryBalanceService,
                validationService
        );

        Reservation releasedReservation = service.release(reservation.getId());

        assertEquals(ReservationStatus.RELEASED, releasedReservation.getStatus());
        verify(inventoryBalanceService).releaseReserved(reservation.getMaterial().getId(), reservation.getWarehouse().getId(), reservation.getQuantity());
    }

    @Test
    void reservationEngineUpdateReleasesPreviousBalanceAndReservesNewBalance() {
        ReservationRepository reservationRepository = mock(ReservationRepository.class);
        MaterialRepository materialRepository = mock(MaterialRepository.class);
        WarehouseRepository warehouseRepository = mock(WarehouseRepository.class);
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        InventoryBalanceService inventoryBalanceService = mock(InventoryBalanceService.class);
        InventoryValidationService validationService = mock(InventoryValidationService.class);
        Reservation existingReservation = reservation();
        Material material = existingReservation.getMaterial();
        Warehouse oldWarehouse = existingReservation.getWarehouse();
        Warehouse newWarehouse = warehouse("WH-NEW");
        Project project = existingReservation.getProject();
        Reservation requestedReservation = Reservation.builder()
                .material(material)
                .warehouse(newWarehouse)
                .project(project)
                .quantity(new BigDecimal("5.000000"))
                .build();
        when(reservationRepository.findById(existingReservation.getId())).thenReturn(Optional.of(existingReservation));
        when(materialRepository.findById(material.getId())).thenReturn(Optional.of(material));
        when(warehouseRepository.findById(newWarehouse.getId())).thenReturn(Optional.of(newWarehouse));
        when(projectRepository.findById(project.getId())).thenReturn(Optional.of(project));
        ReservationEngineImpl service = new ReservationEngineImpl(
                reservationRepository,
                materialRepository,
                warehouseRepository,
                projectRepository,
                inventoryBalanceService,
                validationService
        );

        Reservation updatedReservation = service.update(existingReservation.getId(), requestedReservation);

        assertSame(newWarehouse, updatedReservation.getWarehouse());
        assertEquals(new BigDecimal("5.000000"), updatedReservation.getQuantity());
        verify(inventoryBalanceService).releaseReserved(material.getId(), oldWarehouse.getId(), new BigDecimal("2.000000"));
        verify(inventoryBalanceService).reserve(material.getId(), newWarehouse.getId(), new BigDecimal("5.000000"));
    }

    @Test
    void transferCreatesRelatedMovementsAndIsTransactional() throws NoSuchMethodException {
        MaterialRepository materialRepository = mock(MaterialRepository.class);
        WarehouseRepository warehouseRepository = mock(WarehouseRepository.class);
        StockMovementRepository stockMovementRepository = mock(StockMovementRepository.class);
        StockMovementMapper stockMovementMapper = mock(StockMovementMapper.class);
        InventoryBalanceService inventoryBalanceService = mock(InventoryBalanceService.class);
        InventoryValidationService validationService = mock(InventoryValidationService.class);
        Material material = material("MAT-TRF");
        Warehouse sourceWarehouse = warehouse("WH-SRC");
        Warehouse targetWarehouse = warehouse("WH-DST");
        StockMovementTransferRequest request = new StockMovementTransferRequest(
                material.getId(),
                sourceWarehouse.getId(),
                targetWarehouse.getId(),
                new BigDecimal("4.000000"),
                null,
                "TRF-1",
                "internal transfer"
        );
        StockMovement outgoingMovement = movement(StockMovementType.OUTGOING_TRANSFER, request.quantity());
        StockMovement incomingMovement = movement(StockMovementType.INCOMING_TRANSFER, request.quantity());
        when(materialRepository.findById(material.getId())).thenReturn(Optional.of(material));
        when(warehouseRepository.findById(sourceWarehouse.getId())).thenReturn(Optional.of(sourceWarehouse));
        when(warehouseRepository.findById(targetWarehouse.getId())).thenReturn(Optional.of(targetWarehouse));
        when(stockMovementMapper.toOutgoingTransferEntity(request)).thenReturn(outgoingMovement);
        when(stockMovementMapper.toIncomingTransferEntity(request)).thenReturn(incomingMovement);
        when(stockMovementRepository.save(any(StockMovement.class))).thenAnswer(invocation -> invocation.getArgument(0));
        TransferServiceImpl service = new TransferServiceImpl(
                materialRepository,
                warehouseRepository,
                stockMovementRepository,
                stockMovementMapper,
                inventoryBalanceService,
                validationService
        );

        var movements = service.transfer(request);

        assertEquals(2, movements.size());
        assertSame(incomingMovement, outgoingMovement.getRelatedMovement());
        assertSame(outgoingMovement, incomingMovement.getRelatedMovement());
        assertNotNull(TransferServiceImpl.class.getDeclaredMethod("transfer", StockMovementTransferRequest.class).getAnnotation(Transactional.class));
        verify(inventoryBalanceService).decreasePhysicalAndAvailable(material.getId(), sourceWarehouse.getId(), request.quantity());
        verify(inventoryBalanceService).increasePhysical(material.getId(), targetWarehouse.getId(), request.quantity());
    }

    @Test
    void stockMovementEntryIncreasesPhysicalAndAvailableBalance() {
        StockMovementRepository stockMovementRepository = mock(StockMovementRepository.class);
        MaterialRepository materialRepository = mock(MaterialRepository.class);
        WarehouseRepository warehouseRepository = mock(WarehouseRepository.class);
        StockMovementMapper stockMovementMapper = mock(StockMovementMapper.class);
        InventoryBalanceService inventoryBalanceService = mock(InventoryBalanceService.class);
        InventoryValidationService validationService = mock(InventoryValidationService.class);
        Material material = material("MAT-ENT");
        Warehouse warehouse = warehouse("WH-ENT");
        StockMovementEntryRequest request = new StockMovementEntryRequest(
                material.getId(),
                warehouse.getId(),
                null,
                new BigDecimal("6.000000"),
                null,
                "ENT-1",
                null
        );
        StockMovement movement = movement(StockMovementType.ENTRY, request.quantity());
        when(stockMovementMapper.toEntryEntity(request)).thenReturn(movement);
        when(materialRepository.findById(material.getId())).thenReturn(Optional.of(material));
        when(warehouseRepository.findById(warehouse.getId())).thenReturn(Optional.of(warehouse));
        when(stockMovementRepository.save(movement)).thenReturn(movement);
        StockMovementServiceImpl service = new StockMovementServiceImpl(
                stockMovementRepository,
                materialRepository,
                warehouseRepository,
                mock(SupplierRepository.class),
                mock(ProjectRepository.class),
                mock(ReservationRepository.class),
                stockMovementMapper,
                inventoryBalanceService,
                validationService,
                mock(ReservationEngine.class)
        );

        service.registerEntry(request);

        verify(stockMovementRepository).save(movement);
        verify(inventoryBalanceService).increasePhysical(material.getId(), warehouse.getId(), request.quantity());
    }

    @Test
    void stockMovementOutputWithoutReservationDecreasesPhysicalAndAvailableBalance() {
        StockMovementRepository stockMovementRepository = mock(StockMovementRepository.class);
        MaterialRepository materialRepository = mock(MaterialRepository.class);
        WarehouseRepository warehouseRepository = mock(WarehouseRepository.class);
        StockMovementMapper stockMovementMapper = mock(StockMovementMapper.class);
        InventoryBalanceService inventoryBalanceService = mock(InventoryBalanceService.class);
        InventoryValidationService validationService = mock(InventoryValidationService.class);
        Material material = material("MAT-OUT");
        Warehouse warehouse = warehouse("WH-OUT");
        StockMovementOutputRequest request = new StockMovementOutputRequest(
                material.getId(),
                warehouse.getId(),
                null,
                null,
                new BigDecimal("2.000000"),
                null,
                "OUT-1",
                null
        );
        StockMovement movement = movement(StockMovementType.OUTPUT, request.quantity());
        when(stockMovementMapper.toOutputEntity(request)).thenReturn(movement);
        when(materialRepository.findById(material.getId())).thenReturn(Optional.of(material));
        when(warehouseRepository.findById(warehouse.getId())).thenReturn(Optional.of(warehouse));
        when(stockMovementRepository.save(movement)).thenReturn(movement);
        StockMovementServiceImpl service = new StockMovementServiceImpl(
                stockMovementRepository,
                materialRepository,
                warehouseRepository,
                mock(SupplierRepository.class),
                mock(ProjectRepository.class),
                mock(ReservationRepository.class),
                stockMovementMapper,
                inventoryBalanceService,
                validationService,
                mock(ReservationEngine.class)
        );

        service.registerOutput(request);

        verify(inventoryBalanceService).decreasePhysicalAndAvailable(material.getId(), warehouse.getId(), request.quantity());
        verify(stockMovementRepository).save(movement);
    }

    @Test
    void stockCalculationReturnsZeroWhenRepositoriesHaveNoRows() {
        InventoryBalanceRepository inventoryBalanceRepository = mock(InventoryBalanceRepository.class);
        UUID materialId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        when(inventoryBalanceRepository.calculateAvailableQuantity(materialId, warehouseId, BigDecimal.ZERO)).thenReturn(BigDecimal.ZERO);
        StockCalculationServiceImpl service = new StockCalculationServiceImpl(
                inventoryBalanceRepository,
                mock(StockMovementRepository.class),
                mock(MaterialRepository.class),
                mock(WarehouseRepository.class),
                mock(MaterialMapper.class),
                mock(WarehouseMapper.class)
        );

        assertEquals(BigDecimal.ZERO, service.calculateAvailableStock(materialId, warehouseId));
    }

    @Test
    void bomCalculationRejectsAssembliesWithoutComponents() {
        AssemblyRepository assemblyRepository = mock(AssemblyRepository.class);
        WarehouseRepository warehouseRepository = mock(WarehouseRepository.class);
        InventoryValidationService validationService = mock(InventoryValidationService.class);
        Assembly assembly = Assembly.builder().code("ASM-EMPTY").name("Empty").build();
        Warehouse warehouse = warehouse("WH-BOM");
        setId(assembly, UUID.randomUUID());
        when(assemblyRepository.findWithComponentsById(assembly.getId())).thenReturn(Optional.of(assembly));
        when(warehouseRepository.findById(warehouse.getId())).thenReturn(Optional.of(warehouse));
        org.mockito.Mockito.doThrow(new AssemblyException("Assembly must contain at least one BOM component"))
                .when(validationService).validateAssemblyHasComponents(assembly);
        BOMCalculationServiceImpl service = new BOMCalculationServiceImpl(
                assemblyRepository,
                warehouseRepository,
                mock(StockCalculationService.class),
                validationService,
                mock(AssemblyMapper.class),
                mock(MaterialMapper.class),
                mock(WarehouseMapper.class)
        );

        assertThrows(AssemblyException.class, () -> service.calculateAvailability(assembly.getId(), warehouse.getId()));
    }

    @Test
    void reservationEngineRejectsLifecycleChangesForInactiveReservations() {
        ReservationRepository reservationRepository = mock(ReservationRepository.class);
        InventoryValidationService validationService = mock(InventoryValidationService.class);
        Reservation reservation = reservation();
        reservation.cancel(java.time.Instant.parse("2026-08-01T10:00:00Z"));
        when(reservationRepository.findById(reservation.getId())).thenReturn(Optional.of(reservation));
        org.mockito.Mockito.doThrow(new ReservationException("Reservation is not active."))
                .when(validationService).validateReservationCanChange(reservation);
        ReservationEngineImpl service = new ReservationEngineImpl(
                reservationRepository,
                mock(MaterialRepository.class),
                mock(WarehouseRepository.class),
                mock(ProjectRepository.class),
                mock(InventoryBalanceService.class),
                validationService
        );

        assertThrows(ReservationException.class, () -> service.release(reservation.getId()));
    }

    @Test
    void transferRejectsSameSourceAndTargetWarehouse() {
        MaterialRepository materialRepository = mock(MaterialRepository.class);
        WarehouseRepository warehouseRepository = mock(WarehouseRepository.class);
        StockMovementRepository stockMovementRepository = mock(StockMovementRepository.class);
        InventoryValidationService validationService = mock(InventoryValidationService.class);
        Material material = material("MAT-SAME");
        Warehouse warehouse = warehouse("WH-SAME");
        StockMovementTransferRequest request = new StockMovementTransferRequest(
                material.getId(),
                warehouse.getId(),
                warehouse.getId(),
                BigDecimal.ONE,
                null,
                "TRF-SAME",
                null
        );
        org.mockito.Mockito.doThrow(new WarehouseException("Source and destination warehouses must be different"))
                .when(validationService).validateDifferentWarehouses(warehouse.getId(), warehouse.getId());
        TransferServiceImpl service = new TransferServiceImpl(
                materialRepository,
                warehouseRepository,
                stockMovementRepository,
                mock(StockMovementMapper.class),
                mock(InventoryBalanceService.class),
                validationService
        );

        assertThrows(WarehouseException.class, () -> service.transfer(request));
    }

    @Test
    void inventoryValidationRejectsInsufficientAvailableStock() {
        StockCalculationService stockCalculationService = mock(StockCalculationService.class);
        UUID materialId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        when(stockCalculationService.calculateAvailableStock(materialId, warehouseId)).thenReturn(new BigDecimal("1.000000"));
        InventoryValidationServiceImpl service = new InventoryValidationServiceImpl(
                mock(MaterialRepository.class),
                mock(AssemblyRepository.class),
                mock(WarehouseRepository.class),
                mock(SupplierRepository.class),
                mock(ProjectRepository.class),
                stockCalculationService
        );

        assertThrows(InsufficientStockException.class,
                () -> service.validateAvailableStock(materialId, warehouseId, new BigDecimal("2.000000")));
    }

    @Test
    void inventoryBalanceServiceEntryCreatesBalanceAndIncreasesPhysicalAndAvailable() {
        InventoryBalanceRepository inventoryBalanceRepository = mock(InventoryBalanceRepository.class);
        AuditorAware<String> auditorAware = () -> Optional.of("test-user");
        UUID materialId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        BigDecimal quantity = new BigDecimal("7.000000");
        when(inventoryBalanceRepository.increasePhysical(materialId, warehouseId, quantity, "test-user")).thenReturn(1);
        InventoryBalanceServiceImpl service = new InventoryBalanceServiceImpl(inventoryBalanceRepository, auditorAware);

        service.increasePhysical(materialId, warehouseId, quantity);

        verify(inventoryBalanceRepository).insertZeroBalanceIfMissing(materialId, warehouseId, "test-user");
        verify(inventoryBalanceRepository).increasePhysical(materialId, warehouseId, quantity, "test-user");
    }

    @Test
    void inventoryBalanceServiceOutputRejectsInsufficientProjectedAvailableStock() {
        InventoryBalanceRepository inventoryBalanceRepository = mock(InventoryBalanceRepository.class);
        AuditorAware<String> auditorAware = () -> Optional.of("test-user");
        UUID materialId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        BigDecimal quantity = new BigDecimal("3.000000");
        when(inventoryBalanceRepository.decreasePhysicalAndAvailable(materialId, warehouseId, quantity, "test-user")).thenReturn(0);
        when(inventoryBalanceRepository.calculateAvailableQuantity(materialId, warehouseId, BigDecimal.ZERO)).thenReturn(BigDecimal.ONE);
        InventoryBalanceServiceImpl service = new InventoryBalanceServiceImpl(inventoryBalanceRepository, auditorAware);

        assertThrows(InsufficientStockException.class,
                () -> service.decreasePhysicalAndAvailable(materialId, warehouseId, quantity));
    }

    @Test
    void inventoryBalanceServiceFallsBackToSystemWhenAuditorIsEmpty() {
        InventoryBalanceRepository inventoryBalanceRepository = mock(InventoryBalanceRepository.class);
        AuditorAware<String> auditorAware = Optional::empty;
        UUID materialId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        BigDecimal quantity = new BigDecimal("2.000000");
        when(inventoryBalanceRepository.reserve(materialId, warehouseId, quantity, "system")).thenReturn(1);
        InventoryBalanceServiceImpl service = new InventoryBalanceServiceImpl(inventoryBalanceRepository, auditorAware);

        service.reserve(materialId, warehouseId, quantity);

        verify(inventoryBalanceRepository).reserve(materialId, warehouseId, quantity, "system");
    }

    @Test
    void inventoryBalanceServiceFallsBackToSystemWhenAuditorIsBlank() {
        InventoryBalanceRepository inventoryBalanceRepository = mock(InventoryBalanceRepository.class);
        AuditorAware<String> auditorAware = () -> Optional.of("   ");
        UUID materialId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        BigDecimal quantity = new BigDecimal("2.000000");
        when(inventoryBalanceRepository.releaseReserved(materialId, warehouseId, quantity, "system")).thenReturn(1);
        InventoryBalanceServiceImpl service = new InventoryBalanceServiceImpl(inventoryBalanceRepository, auditorAware);

        service.releaseReserved(materialId, warehouseId, quantity);

        verify(inventoryBalanceRepository).releaseReserved(materialId, warehouseId, quantity, "system");
    }

    @Test
    void restControllersRemainInInfrastructureWebLayer() throws IOException {
        Path mainSources = Path.of("src", "main", "java");

        try (var paths = Files.walk(mainSources)) {
            assertTrue(paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(BusinessLayerTest::containsRestController)
                    .allMatch(path -> path.toString().contains(Path.of("infrastructure", "web", "controller").toString())));
        }
    }

    private static boolean containsRestController(Path path) {
        try {
            return REST_CONTROLLER_ANNOTATION.matcher(Files.readString(path)).find();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static AssemblyComponent component(Material material, String quantity) {
        return AssemblyComponent.builder()
                .material(material)
                .quantity(new BigDecimal(quantity))
                .build();
    }

    private static StockMovement movement(StockMovementType type, BigDecimal quantity) {
        return StockMovement.builder()
                .type(type)
                .quantity(quantity)
                .build();
    }

    private static Reservation reservation() {
        Reservation reservation = Reservation.builder()
                .material(material("MAT-RES"))
                .warehouse(warehouse("WH-RES"))
                .project(Project.builder().code("PRJ-001").name("Project").build())
                .quantity(new BigDecimal("2.000000"))
                .build();
        setId(reservation, UUID.randomUUID());
        return reservation;
    }

    private static Material material(String code) {
        Material material = Material.builder()
                .code(code)
                .name("Material " + code)
                .unitOfMeasure("unit")
                .minimumStockLevel(BigDecimal.ZERO)
                .build();
        setId(material, UUID.randomUUID());
        return material;
    }

    private static Warehouse warehouse(String code) {
        Warehouse warehouse = Warehouse.builder()
                .code(code)
                .name("Warehouse " + code)
                .build();
        setId(warehouse, UUID.randomUUID());
        return warehouse;
    }

    private static MaterialSummaryResponse materialSummary(Material material) {
        return new MaterialSummaryResponse(material.getId(), material.getCode(), material.getName(), material.getUnitOfMeasure(), material.getActive());
    }

    private static void setId(Object entity, UUID id) {
        ReflectionTestUtils.setField(entity, "id", id);
    }
}