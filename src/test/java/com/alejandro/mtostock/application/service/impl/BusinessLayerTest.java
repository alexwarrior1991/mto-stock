package com.alejandro.mtostock.application.service.impl;

import com.alejandro.mtostock.application.service.EntityAuditService;
import com.alejandro.mtostock.application.dto.assembly.AssemblyAvailabilityResponse;
import com.alejandro.mtostock.application.dto.assembly.AssemblyComponentRequest;
import com.alejandro.mtostock.application.dto.assembly.AssemblyRequest;
import com.alejandro.mtostock.application.dto.assembly.AssemblySummaryResponse;
import com.alejandro.mtostock.application.dto.material.MaterialRequest;
import com.alejandro.mtostock.application.dto.material.MaterialStockResponse;
import com.alejandro.mtostock.application.dto.material.MaterialSummaryResponse;
import com.alejandro.mtostock.application.dto.material.MaterialUpdateRequest;
import com.alejandro.mtostock.application.dto.messaging.InboxMessageCommand;
import com.alejandro.mtostock.application.dto.messaging.InboxProcessingResult;
import com.alejandro.mtostock.application.dto.messaging.MasterDataChangedEvent;
import com.alejandro.mtostock.application.dto.messaging.MasterDataChangedMessage;
import com.alejandro.mtostock.application.dto.messaging.MasterDataEntityNames;
import com.alejandro.mtostock.application.dto.messaging.MasterDataEventContext;
import com.alejandro.mtostock.application.dto.messaging.MasterDataOperation;
import com.alejandro.mtostock.application.dto.project.ProjectUpdateRequest;
import com.alejandro.mtostock.application.dto.reservation.ReservationRequest;
import com.alejandro.mtostock.application.dto.reservation.ReservationResponse;
import com.alejandro.mtostock.application.dto.reservation.ReservationStatusDto;
import com.alejandro.mtostock.application.dto.stock.StockAdjustmentDirection;
import com.alejandro.mtostock.application.dto.stock.StockMovementAdjustmentRequest;
import com.alejandro.mtostock.application.dto.stock.StockMovementEntryRequest;
import com.alejandro.mtostock.application.dto.stock.StockMovementOutputRequest;
import com.alejandro.mtostock.application.dto.stock.StockMovementResponse;
import com.alejandro.mtostock.application.dto.stock.StockMovementTransferRequest;
import com.alejandro.mtostock.application.dto.stock.StockMovementTypeDto;
import com.alejandro.mtostock.application.dto.supplier.SupplierUpdateRequest;
import com.alejandro.mtostock.application.dto.warehouse.WarehouseSummaryResponse;
import com.alejandro.mtostock.application.dto.warehouse.WarehouseUpdateRequest;
import com.alejandro.mtostock.application.exception.AssemblyException;
import com.alejandro.mtostock.application.exception.DuplicateCodeException;
import com.alejandro.mtostock.application.exception.IdempotencyKeyConflictException;
import com.alejandro.mtostock.application.exception.InsufficientStockException;
import com.alejandro.mtostock.application.dto.messaging.DomainEvent;
import com.alejandro.mtostock.application.service.DomainEventPublisher;
import com.alejandro.mtostock.application.exception.NotFoundException;
import com.alejandro.mtostock.application.exception.ProjectException;
import com.alejandro.mtostock.application.exception.ReservationException;
import com.alejandro.mtostock.application.exception.ValidationException;
import com.alejandro.mtostock.application.exception.WarehouseException;
import com.alejandro.mtostock.application.mapper.AssemblyMapper;
import com.alejandro.mtostock.application.mapper.MaterialMapper;
import com.alejandro.mtostock.application.mapper.ProjectMapper;
import com.alejandro.mtostock.application.mapper.ReservationMapper;
import com.alejandro.mtostock.application.mapper.StockMovementMapper;
import com.alejandro.mtostock.application.mapper.SupplierMapper;
import com.alejandro.mtostock.application.mapper.WarehouseMapper;
import com.alejandro.mtostock.application.service.BOMCalculationService;
import com.alejandro.mtostock.application.service.IdempotentRequestService;
import com.alejandro.mtostock.application.service.InboxMessageService;
import com.alejandro.mtostock.application.service.InventoryBalanceService;
import com.alejandro.mtostock.application.service.InventoryValidationService;
import com.alejandro.mtostock.application.service.MasterDataEntityHandler;
import com.alejandro.mtostock.application.service.MasterDataEventHandler;
import com.alejandro.mtostock.application.service.ReservationEngine;
import com.alejandro.mtostock.application.service.StockCalculationService;
import com.alejandro.mtostock.application.service.TransferService;
import com.alejandro.mtostock.configuration.cache.CacheInvalidator;
import com.alejandro.mtostock.configuration.cache.CacheNames;
import com.alejandro.mtostock.configuration.idempotency.IdempotencyProperties;
import com.alejandro.mtostock.configuration.idempotency.IdempotencyPurgeConfiguration;
import com.alejandro.mtostock.infrastructure.persistence.entity.Assembly;
import com.alejandro.mtostock.infrastructure.persistence.entity.AssemblyComponent;
import com.alejandro.mtostock.infrastructure.persistence.entity.EntityReferenceFactory;
import com.alejandro.mtostock.infrastructure.persistence.entity.IdempotentOperation;
import com.alejandro.mtostock.infrastructure.persistence.entity.IdempotentRequest;
import com.alejandro.mtostock.infrastructure.persistence.entity.Material;
import com.alejandro.mtostock.infrastructure.persistence.entity.Project;
import com.alejandro.mtostock.infrastructure.persistence.entity.Reservation;
import com.alejandro.mtostock.infrastructure.persistence.entity.ReservationStatus;
import com.alejandro.mtostock.infrastructure.persistence.entity.StockMovement;
import com.alejandro.mtostock.infrastructure.persistence.entity.StockMovementType;
import com.alejandro.mtostock.infrastructure.persistence.entity.Supplier;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.domain.AuditorAware;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BusinessLayerTest {

    private static final Pattern REST_CONTROLLER_ANNOTATION = Pattern.compile("@RestController(?!Advice)\\b");

    private static final String MESSAGE_ID = "0f8b1f4c-3f6a-4a6d-9a2a-1c9f5f6f2b10";
    private static final String SOURCE_SERVICE = "mto-configuration";
    private static final String PAYLOAD = "{\"eventType\":\"MASTER_DATA_STATION_UPDATED\"}";

    private static final MasterDataEventContext CONTEXT = new MasterDataEventContext(7L);

    @Test
    void stockCalculationReadsCurrentBalancesFromInventoryBalance() {
        InventoryBalanceRepository inventoryBalanceRepository = mock(InventoryBalanceRepository.class);
        UUID materialId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        when(inventoryBalanceRepository.calculatePhysicalQuantity(materialId, warehouseId, BigDecimal.ZERO))
                .thenReturn(new BigDecimal("8.000000"));
        when(inventoryBalanceRepository.calculateReservedQuantity(materialId, warehouseId, BigDecimal.ZERO))
                .thenReturn(new BigDecimal("3.000000"));
        when(inventoryBalanceRepository.calculateAvailableQuantity(materialId, warehouseId, BigDecimal.ZERO))
                .thenReturn(new BigDecimal("5.000000"));
        StockCalculationServiceImpl service = new StockCalculationServiceImpl(
                inventoryBalanceRepository,
                mock(MaterialRepository.class),
                mock(WarehouseRepository.class),
                mock(MaterialMapper.class),
                mock(WarehouseMapper.class)
        );

        assertEquals(new BigDecimal("8.000000"), service.calculatePhysicalStock(materialId, warehouseId));
        assertEquals(new BigDecimal("3.000000"), service.calculateReservedStock(materialId, warehouseId));
        assertEquals(new BigDecimal("5.000000"), service.calculateAvailableStock(materialId, warehouseId));
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
                validationService,
                new RecordingEventPublisher()
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
                validationService,
                new RecordingEventPublisher()
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
                validationService,
                new RecordingEventPublisher()
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
        verify(inventoryBalanceService).transfer(material.getId(), sourceWarehouse.getId(), targetWarehouse.getId(), request.quantity());
        verify(inventoryBalanceService, never()).decreasePhysicalAndAvailable(any(), any(), any());
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
                mock(ReservationEngine.class),
                mock(IdempotentRequestService.class),
                new RecordingEventPublisher()
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
                mock(ReservationEngine.class),
                mock(IdempotentRequestService.class),
                new RecordingEventPublisher()
        );

        service.registerOutput(request, null);

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
                validationService,
                new RecordingEventPublisher()
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
    void inventoryBalanceServiceEntryCreatesBalanceAndIncreasesPhysicalAndAvailable() {
        InventoryBalanceRepository inventoryBalanceRepository = mock(InventoryBalanceRepository.class);
        AuditorAware<String> auditorAware = () -> Optional.of("test-user");
        UUID materialId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        BigDecimal quantity = new BigDecimal("7.000000");
        when(inventoryBalanceRepository.increasePhysical(materialId, warehouseId, quantity, "test-user")).thenReturn(1);
        InventoryBalanceServiceImpl service = balanceService(inventoryBalanceRepository, auditorAware);

        service.increasePhysical(materialId, warehouseId, quantity);

        verify(inventoryBalanceRepository).insertZeroBalanceIfMissing(materialId, warehouseId, "test-user");
        verify(inventoryBalanceRepository).increasePhysical(materialId, warehouseId, quantity, "test-user");
    }

    @Test
    void inventoryBalanceServiceOutputRejectsInsufficientProjectedAvailableStock() {
        InventoryBalanceRepository inventoryBalanceRepository = mock(InventoryBalanceRepository.class);
        AuditorAware<String> auditorAware = () -> Optional.of("test-user");
        Material material = material("MAT-OUT-LOW");
        UUID materialId = material.getId();
        UUID warehouseId = UUID.randomUUID();
        BigDecimal quantity = new BigDecimal("3.000000");
        when(inventoryBalanceRepository.decreasePhysicalAndAvailable(materialId, warehouseId, quantity, "test-user")).thenReturn(0);
        when(inventoryBalanceRepository.calculateAvailableQuantity(materialId, warehouseId, BigDecimal.ZERO)).thenReturn(BigDecimal.ONE);
        InventoryBalanceServiceImpl service = balanceService(inventoryBalanceRepository, auditorAware, material);

        assertThrows(InsufficientStockException.class,
                () -> service.decreasePhysicalAndAvailable(materialId, warehouseId, quantity));
    }

    @Test
    void inventoryBalanceServiceFallsBackToSystemWhenAuditorIsEmpty() {
        InventoryBalanceRepository inventoryBalanceRepository = mock(InventoryBalanceRepository.class);
        AuditorAware<String> auditorAware = Optional::empty;
        Material material = material("MAT-RES-SYS");
        UUID materialId = material.getId();
        UUID warehouseId = UUID.randomUUID();
        BigDecimal quantity = new BigDecimal("2.000000");
        when(inventoryBalanceRepository.reserve(materialId, warehouseId, quantity, "system")).thenReturn(1);
        InventoryBalanceServiceImpl service = balanceService(inventoryBalanceRepository, auditorAware, material);

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
        InventoryBalanceServiceImpl service = balanceService(inventoryBalanceRepository, auditorAware);

        service.releaseReserved(materialId, warehouseId, quantity);

        verify(inventoryBalanceRepository).releaseReserved(materialId, warehouseId, quantity, "system");
    }

    @Test
    void stockMovementPositiveAdjustmentValidatesQuantityAndIncreasesBalance() {
        StockMovementRepository stockMovementRepository = mock(StockMovementRepository.class);
        MaterialRepository materialRepository = mock(MaterialRepository.class);
        WarehouseRepository warehouseRepository = mock(WarehouseRepository.class);
        StockMovementMapper stockMovementMapper = mock(StockMovementMapper.class);
        InventoryBalanceService inventoryBalanceService = mock(InventoryBalanceService.class);
        InventoryValidationService validationService = mock(InventoryValidationService.class);
        Material material = material("MAT-ADJ-POS");
        Warehouse warehouse = warehouse("WH-ADJ-POS");
        StockMovementAdjustmentRequest request = new StockMovementAdjustmentRequest(
                material.getId(),
                warehouse.getId(),
                StockAdjustmentDirection.POSITIVE,
                new BigDecimal("3.000000"),
                null,
                "ADJ-POS-1",
                null
        );
        StockMovement movement = movement(StockMovementType.POSITIVE_ADJUSTMENT, request.quantity());
        setId(movement, UUID.randomUUID());
        when(stockMovementMapper.toAdjustmentEntity(request)).thenReturn(movement);
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
                mock(ReservationEngine.class),
                mock(IdempotentRequestService.class),
                new RecordingEventPublisher()
        );

        service.registerAdjustment(request);

        verify(validationService).validatePositiveQuantity(request.quantity());
        verify(inventoryBalanceService).increasePhysical(material.getId(), warehouse.getId(), request.quantity());
        verify(inventoryBalanceService, never()).decreasePhysicalAndAvailable(any(), any(), any());
        verify(stockMovementRepository).save(movement);
    }

    @Test
    void stockMovementNegativeAdjustmentDecreasesBalanceBeforeStoringMovement() {
        StockMovementRepository stockMovementRepository = mock(StockMovementRepository.class);
        MaterialRepository materialRepository = mock(MaterialRepository.class);
        WarehouseRepository warehouseRepository = mock(WarehouseRepository.class);
        StockMovementMapper stockMovementMapper = mock(StockMovementMapper.class);
        InventoryBalanceService inventoryBalanceService = mock(InventoryBalanceService.class);
        InventoryValidationService validationService = mock(InventoryValidationService.class);
        Material material = material("MAT-ADJ-NEG");
        Warehouse warehouse = warehouse("WH-ADJ-NEG");
        StockMovementAdjustmentRequest request = new StockMovementAdjustmentRequest(
                material.getId(),
                warehouse.getId(),
                StockAdjustmentDirection.NEGATIVE,
                new BigDecimal("1.500000"),
                null,
                "ADJ-NEG-1",
                null
        );
        StockMovement movement = movement(StockMovementType.NEGATIVE_ADJUSTMENT, request.quantity());
        setId(movement, UUID.randomUUID());
        when(stockMovementMapper.toAdjustmentEntity(request)).thenReturn(movement);
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
                mock(ReservationEngine.class),
                mock(IdempotentRequestService.class),
                new RecordingEventPublisher()
        );

        service.registerAdjustment(request);

        verify(inventoryBalanceService).decreasePhysicalAndAvailable(material.getId(), warehouse.getId(), request.quantity());
        verify(inventoryBalanceService, never()).increasePhysical(any(), any(), any());
        verify(stockMovementRepository).save(movement);
    }

    @Test
    void stockMovementOutputWithReservationConsumesItInsteadOfDecreasingAvailableBalance() {
        StockMovementRepository stockMovementRepository = mock(StockMovementRepository.class);
        MaterialRepository materialRepository = mock(MaterialRepository.class);
        WarehouseRepository warehouseRepository = mock(WarehouseRepository.class);
        ReservationRepository reservationRepository = mock(ReservationRepository.class);
        StockMovementMapper stockMovementMapper = mock(StockMovementMapper.class);
        InventoryBalanceService inventoryBalanceService = mock(InventoryBalanceService.class);
        InventoryValidationService validationService = mock(InventoryValidationService.class);
        ReservationEngine reservationEngine = mock(ReservationEngine.class);
        Reservation reservation = reservation();
        Material material = reservation.getMaterial();
        Warehouse warehouse = reservation.getWarehouse();
        StockMovementOutputRequest request = new StockMovementOutputRequest(
                material.getId(),
                warehouse.getId(),
                null,
                reservation.getId(),
                new BigDecimal("2.000000"),
                null,
                "OUT-RES-1",
                null
        );
        StockMovement movement = movement(StockMovementType.OUTPUT, request.quantity());
        when(stockMovementMapper.toOutputEntity(request)).thenReturn(movement);
        when(materialRepository.findById(material.getId())).thenReturn(Optional.of(material));
        when(warehouseRepository.findById(warehouse.getId())).thenReturn(Optional.of(warehouse));
        when(reservationRepository.findById(reservation.getId())).thenReturn(Optional.of(reservation));
        when(stockMovementRepository.save(movement)).thenReturn(movement);
        StockMovementServiceImpl service = new StockMovementServiceImpl(
                stockMovementRepository,
                materialRepository,
                warehouseRepository,
                mock(SupplierRepository.class),
                mock(ProjectRepository.class),
                reservationRepository,
                stockMovementMapper,
                inventoryBalanceService,
                validationService,
                reservationEngine,
                mock(IdempotentRequestService.class),
                new RecordingEventPublisher()
        );

        service.registerOutput(request, null);

        assertSame(reservation, movement.getReservation());
        verify(validationService).validateReservationCanChange(reservation);
        verify(reservationEngine).consume(reservation.getId());
        verify(inventoryBalanceService, never()).decreasePhysicalAndAvailable(any(), any(), any());
    }

    @Test
    void stockMovementOutputRejectsReservationFromAnotherMaterial() {
        StockMovementRepository stockMovementRepository = mock(StockMovementRepository.class);
        MaterialRepository materialRepository = mock(MaterialRepository.class);
        WarehouseRepository warehouseRepository = mock(WarehouseRepository.class);
        ReservationRepository reservationRepository = mock(ReservationRepository.class);
        StockMovementMapper stockMovementMapper = mock(StockMovementMapper.class);
        Reservation reservation = reservation();
        Warehouse warehouse = reservation.getWarehouse();
        Material otherMaterial = material("MAT-OTHER");
        StockMovementOutputRequest request = new StockMovementOutputRequest(
                otherMaterial.getId(),
                warehouse.getId(),
                null,
                reservation.getId(),
                new BigDecimal("2.000000"),
                null,
                "OUT-RES-2",
                null
        );
        when(stockMovementMapper.toOutputEntity(request)).thenReturn(movement(StockMovementType.OUTPUT, request.quantity()));
        when(materialRepository.findById(otherMaterial.getId())).thenReturn(Optional.of(otherMaterial));
        when(warehouseRepository.findById(warehouse.getId())).thenReturn(Optional.of(warehouse));
        when(reservationRepository.findById(reservation.getId())).thenReturn(Optional.of(reservation));
        StockMovementServiceImpl service = new StockMovementServiceImpl(
                stockMovementRepository,
                materialRepository,
                warehouseRepository,
                mock(SupplierRepository.class),
                mock(ProjectRepository.class),
                reservationRepository,
                stockMovementMapper,
                mock(InventoryBalanceService.class),
                mock(InventoryValidationService.class),
                mock(ReservationEngine.class),
                mock(IdempotentRequestService.class),
                new RecordingEventPublisher()
        );

        assertThrows(ReservationException.class, () -> service.registerOutput(request, null));
        verify(stockMovementRepository, never()).save(any(StockMovement.class));
    }

    @Test
    void stockMovementOutputRejectsPartialReservationConsumption() {
        StockMovementRepository stockMovementRepository = mock(StockMovementRepository.class);
        MaterialRepository materialRepository = mock(MaterialRepository.class);
        WarehouseRepository warehouseRepository = mock(WarehouseRepository.class);
        ReservationRepository reservationRepository = mock(ReservationRepository.class);
        StockMovementMapper stockMovementMapper = mock(StockMovementMapper.class);
        Reservation reservation = reservation();
        Material material = reservation.getMaterial();
        Warehouse warehouse = reservation.getWarehouse();
        StockMovementOutputRequest request = new StockMovementOutputRequest(
                material.getId(),
                warehouse.getId(),
                null,
                reservation.getId(),
                new BigDecimal("1.000000"),
                null,
                "OUT-RES-3",
                null
        );
        when(stockMovementMapper.toOutputEntity(request)).thenReturn(movement(StockMovementType.OUTPUT, request.quantity()));
        when(materialRepository.findById(material.getId())).thenReturn(Optional.of(material));
        when(warehouseRepository.findById(warehouse.getId())).thenReturn(Optional.of(warehouse));
        when(reservationRepository.findById(reservation.getId())).thenReturn(Optional.of(reservation));
        StockMovementServiceImpl service = new StockMovementServiceImpl(
                stockMovementRepository,
                materialRepository,
                warehouseRepository,
                mock(SupplierRepository.class),
                mock(ProjectRepository.class),
                reservationRepository,
                stockMovementMapper,
                mock(InventoryBalanceService.class),
                mock(InventoryValidationService.class),
                mock(ReservationEngine.class),
                mock(IdempotentRequestService.class),
                new RecordingEventPublisher()
        );

        assertThrows(ReservationException.class, () -> service.registerOutput(request, null));
        verify(stockMovementRepository, never()).save(any(StockMovement.class));
    }

    @Test
    void stockMovementLookupReportsMissingMovementAsNotFound() {
        StockMovementRepository stockMovementRepository = mock(StockMovementRepository.class);
        UUID missingId = UUID.randomUUID();
        when(stockMovementRepository.findById(missingId)).thenReturn(Optional.empty());
        StockMovementServiceImpl service = new StockMovementServiceImpl(
                stockMovementRepository,
                mock(MaterialRepository.class),
                mock(WarehouseRepository.class),
                mock(SupplierRepository.class),
                mock(ProjectRepository.class),
                mock(ReservationRepository.class),
                mock(StockMovementMapper.class),
                mock(InventoryBalanceService.class),
                mock(InventoryValidationService.class),
                mock(ReservationEngine.class),
                mock(IdempotentRequestService.class),
                new RecordingEventPublisher()
        );

        NotFoundException exception = assertThrows(NotFoundException.class, () -> service.findById(missingId));
        assertEquals("Stock movement", exception.getAggregate());
    }

    /**
     * Un reintento con la misma clave y el mismo cuerpo no vuelve a reservar: la reclamación dice
     * que la petición ya se aplicó, y se devuelve la reserva que creó la primera, tal como está.
     */
    @Test
    void reservationRepeatedWithTheSameIdempotencyKeyReturnsTheFirstOneWithoutReservingAgain() {
        ReservationRepository reservationRepository = mock(ReservationRepository.class);
        ReservationMapper reservationMapper = mock(ReservationMapper.class);
        ReservationEngine reservationEngine = mock(ReservationEngine.class);
        IdempotentRequestService idempotentRequestService = mock(IdempotentRequestService.class);
        Reservation existing = reservation();
        ReservationRequest request = new ReservationRequest(
                existing.getMaterial().getId(), existing.getWarehouse().getId(), UUID.randomUUID(), existing.getQuantity(), null);
        ReservationResponse firstAnswer = reservationResponse(existing);
        when(idempotentRequestService.claim(IdempotentOperation.RESERVATION, "retry-1", request)).thenReturn(Optional.of(existing.getId()));
        when(reservationRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(reservationMapper.toResponse(existing)).thenReturn(firstAnswer);
        ReservationServiceImpl service = new ReservationServiceImpl(reservationRepository, reservationMapper,
                mock(EntityAuditService.class), reservationEngine, new EntityReferenceFactory(), idempotentRequestService);

        assertSame(firstAnswer, service.create(request, "retry-1"));

        verifyNoInteractions(reservationEngine);
        verify(idempotentRequestService, never()).complete(any(), any(), any());
    }

    @Test
    void reservationWithANewIdempotencyKeyRecordsTheReservationItCreated() {
        ReservationMapper reservationMapper = mock(ReservationMapper.class);
        ReservationEngine reservationEngine = mock(ReservationEngine.class);
        IdempotentRequestService idempotentRequestService = mock(IdempotentRequestService.class);
        Reservation created = reservation();
        ReservationRequest request = new ReservationRequest(
                created.getMaterial().getId(), created.getWarehouse().getId(), UUID.randomUUID(), created.getQuantity(), null);
        when(idempotentRequestService.claim(IdempotentOperation.RESERVATION, "first-1", request)).thenReturn(Optional.empty());
        when(reservationMapper.toEntity(request)).thenReturn(created);
        when(reservationEngine.create(created)).thenReturn(created);
        when(reservationMapper.toResponse(created)).thenReturn(reservationResponse(created));
        ReservationServiceImpl service = new ReservationServiceImpl(mock(ReservationRepository.class), reservationMapper,
                mock(EntityAuditService.class), reservationEngine, new EntityReferenceFactory(), idempotentRequestService);

        service.create(request, "first-1");

        InOrder order = inOrder(idempotentRequestService, reservationEngine);
        order.verify(idempotentRequestService).claim(IdempotentOperation.RESERVATION, "first-1", request);
        order.verify(reservationEngine).create(created);
        order.verify(idempotentRequestService).complete(IdempotentOperation.RESERVATION, "first-1", created.getId());
    }

    /**
     * La salida es la que más duele duplicar: descuenta el físico. Repetida con la misma clave, ni
     * toca el saldo ni consume reservas ni escribe un apunte; devuelve el que ya hay.
     */
    @Test
    void stockOutputRepeatedWithTheSameIdempotencyKeyDoesNotTakeTheMaterialOutAgain() {
        StockMovementRepository stockMovementRepository = mock(StockMovementRepository.class);
        StockMovementMapper stockMovementMapper = mock(StockMovementMapper.class);
        InventoryBalanceService inventoryBalanceService = mock(InventoryBalanceService.class);
        ReservationEngine reservationEngine = mock(ReservationEngine.class);
        IdempotentRequestService idempotentRequestService = mock(IdempotentRequestService.class);
        StockMovement existing = movement(StockMovementType.OUTPUT, new BigDecimal("2.000000"));
        setId(existing, UUID.randomUUID());
        StockMovementResponse firstAnswer = movementResponse(existing);
        StockMovementOutputRequest request = new StockMovementOutputRequest(
                UUID.randomUUID(), UUID.randomUUID(), null, null, new BigDecimal("2.000000"), null, "MO-000001", null);
        when(idempotentRequestService.claim(IdempotentOperation.OUTPUT, "retry-2", request)).thenReturn(Optional.of(existing.getId()));
        when(stockMovementRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(stockMovementMapper.toResponse(existing)).thenReturn(firstAnswer);
        StockMovementServiceImpl service = new StockMovementServiceImpl(
                stockMovementRepository,
                mock(MaterialRepository.class),
                mock(WarehouseRepository.class),
                mock(SupplierRepository.class),
                mock(ProjectRepository.class),
                mock(ReservationRepository.class),
                stockMovementMapper,
                inventoryBalanceService,
                mock(InventoryValidationService.class),
                reservationEngine,
                idempotentRequestService,
                new RecordingEventPublisher()
        );

        assertSame(firstAnswer, service.registerOutput(request, "retry-2"));

        verifyNoInteractions(inventoryBalanceService, reservationEngine);
        verify(stockMovementRepository, never()).save(any());
        verify(idempotentRequestService, never()).complete(any(), any(), any());
    }

    @Test
    void stockOutputWithANewIdempotencyKeyRecordsTheMovementItCreated() {
        StockMovementRepository stockMovementRepository = mock(StockMovementRepository.class);
        MaterialRepository materialRepository = mock(MaterialRepository.class);
        WarehouseRepository warehouseRepository = mock(WarehouseRepository.class);
        StockMovementMapper stockMovementMapper = mock(StockMovementMapper.class);
        InventoryBalanceService inventoryBalanceService = mock(InventoryBalanceService.class);
        IdempotentRequestService idempotentRequestService = mock(IdempotentRequestService.class);
        Material material = material("MAT-IDEM");
        Warehouse warehouse = warehouse("WH-IDEM");
        StockMovementOutputRequest request = new StockMovementOutputRequest(
                material.getId(), warehouse.getId(), null, null, new BigDecimal("2.000000"), null, "MO-000001", null);
        StockMovement movement = movement(StockMovementType.OUTPUT, request.quantity());
        setId(movement, UUID.randomUUID());
        when(idempotentRequestService.claim(IdempotentOperation.OUTPUT, "first-2", request)).thenReturn(Optional.empty());
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
                mock(InventoryValidationService.class),
                mock(ReservationEngine.class),
                idempotentRequestService,
                new RecordingEventPublisher()
        );

        service.registerOutput(request, "first-2");

        InOrder order = inOrder(idempotentRequestService, inventoryBalanceService, stockMovementRepository);
        order.verify(idempotentRequestService).claim(IdempotentOperation.OUTPUT, "first-2", request);
        order.verify(inventoryBalanceService).decreasePhysicalAndAvailable(material.getId(), warehouse.getId(), request.quantity());
        order.verify(stockMovementRepository).save(movement);
        order.verify(idempotentRequestService).complete(IdempotentOperation.OUTPUT, "first-2", movement.getId());
    }

    /**
     * La reclamación de verdad la hace el índice único (InventoryRepositoryDataJpaTest); aquí, lo que
     * el servicio hace con la respuesta: 1 es ejecutar, y 0 con el mismo cuerpo es devolver lo creado.
     */
    @Test
    void idempotencyKeyIsClaimedOnceAndARetryWithTheSameBodyGetsWhatTheFirstCreated() {
        IdempotentRequestRepository repository = mock(IdempotentRequestRepository.class);
        IdempotentRequestServiceImpl service = new IdempotentRequestServiceImpl(repository, () -> Optional.of("mto-maintenance-svc"));
        ReservationRequest request = idempotentReservation("2.000000");
        String fingerprint = IdempotentRequestServiceImpl.fingerprint(request);
        UUID created = UUID.randomUUID();
        when(repository.claim("RESERVATION", "key-1", fingerprint, "mto-maintenance-svc")).thenReturn(1, 0);
        when(repository.recordResource("RESERVATION", "key-1", "mto-maintenance-svc", created)).thenReturn(1);
        when(repository.findByOperationAndCreatedByAndIdempotencyKey(IdempotentOperation.RESERVATION, "mto-maintenance-svc", "key-1"))
                .thenReturn(Optional.of(storedRequest(IdempotentOperation.RESERVATION, "key-1", fingerprint, created)));

        assertEquals(Optional.empty(), service.claim(IdempotentOperation.RESERVATION, "key-1", request));
        service.complete(IdempotentOperation.RESERVATION, "key-1", created);
        assertEquals(Optional.of(created), service.claim(IdempotentOperation.RESERVATION, "key-1", request));
    }

    /**
     * Un reintento manda el mismo cuerpo. La misma clave con otro cuerpo es un error del cliente:
     * aplicarla crearía algo que el cliente cree que ya existe, y devolver lo de la primera le diría
     * que se hizo lo que no pidió.
     */
    @Test
    void idempotencyKeyReusedWithAnotherBodyIsAConflictAndRecordsNothing() {
        IdempotentRequestRepository repository = mock(IdempotentRequestRepository.class);
        IdempotentRequestServiceImpl service = new IdempotentRequestServiceImpl(repository, () -> Optional.of("mto-maintenance-svc"));
        ReservationRequest first = idempotentReservation("2.000000");
        ReservationRequest other = new ReservationRequest(first.materialId(), first.warehouseId(), first.projectId(), new BigDecimal("3.000000"), null);
        when(repository.claim("RESERVATION", "key-1", IdempotentRequestServiceImpl.fingerprint(other), "mto-maintenance-svc")).thenReturn(0);
        when(repository.findByOperationAndCreatedByAndIdempotencyKey(IdempotentOperation.RESERVATION, "mto-maintenance-svc", "key-1"))
                .thenReturn(Optional.of(storedRequest(IdempotentOperation.RESERVATION, "key-1",
                        IdempotentRequestServiceImpl.fingerprint(first), UUID.randomUUID())));

        IdempotencyKeyConflictException conflict = assertThrows(IdempotencyKeyConflictException.class,
                () -> service.claim(IdempotentOperation.RESERVATION, "key-1", other));

        assertTrue(conflict.getMessage().contains("key-1"));
        verify(repository, never()).recordResource(any(), any(), any(), any());
    }

    @Test
    void withoutIdempotencyKeyNothingIsClaimedNorRecorded() {
        IdempotentRequestRepository repository = mock(IdempotentRequestRepository.class);
        IdempotentRequestServiceImpl service = new IdempotentRequestServiceImpl(repository, () -> Optional.of("mto-maintenance-svc"));

        assertEquals(Optional.empty(), service.claim(IdempotentOperation.OUTPUT, null, idempotentReservation("1")));
        service.complete(IdempotentOperation.OUTPUT, null, UUID.randomUUID());

        verifyNoInteractions(repository);
    }

    @Test
    void malformedIdempotencyKeysAreRejectedBeforeTouchingTheTable() {
        IdempotentRequestRepository repository = mock(IdempotentRequestRepository.class);
        IdempotentRequestServiceImpl service = new IdempotentRequestServiceImpl(repository, Optional::empty);
        ReservationRequest request = idempotentReservation("1");

        for (String key : List.of("", " ", "two words", "clave-ñ", "k".repeat(256))) {
            assertThrows(ValidationException.class, () -> service.claim(IdempotentOperation.RESERVATION, key, request), key);
        }
        verifyNoInteractions(repository);

        // 255 caracteres ASCII visibles todavía es una clave; sin usuario, el que la manda es 'system'.
        when(repository.claim(eq("RESERVATION"), eq("k".repeat(255)), any(), eq("system"))).thenReturn(1);
        assertEquals(Optional.empty(), service.claim(IdempotentOperation.RESERVATION, "k".repeat(255), request));
    }

    /** La clave es de quien la manda: el mismo usuario que escriben las columnas de auditoría. */
    @Test
    void idempotencyKeyBelongsToTheAuthenticatedCaller() {
        IdempotentRequestRepository repository = mock(IdempotentRequestRepository.class);
        ReservationRequest request = idempotentReservation("1");
        String fingerprint = IdempotentRequestServiceImpl.fingerprint(request);
        when(repository.claim("RESERVATION", "key-1", fingerprint, "warehouse.operator")).thenReturn(1);
        when(repository.claim("RESERVATION", "key-1", fingerprint, "system")).thenReturn(1);

        new IdempotentRequestServiceImpl(repository, () -> Optional.of("  warehouse.operator  "))
                .claim(IdempotentOperation.RESERVATION, "key-1", request);
        new IdempotentRequestServiceImpl(repository, () -> Optional.of("   "))
                .claim(IdempotentOperation.RESERVATION, "key-1", request);

        verify(repository).claim("RESERVATION", "key-1", fingerprint, "warehouse.operator");
        verify(repository).claim("RESERVATION", "key-1", fingerprint, "system");
    }

    /**
     * La huella es cada componente con valor del cuerpo, con su nombre. Un decimal cuenta por su
     * valor: {@code 2} y {@code 2.000000} son la misma cantidad, y un cliente que la escribe de otra
     * forma al reintentar sigue siendo el mismo reintento.
     */
    @Test
    void theRequestFingerprintCountsDecimalsByValueAndEveryFieldThatIsSet() {
        ReservationRequest two = idempotentReservation("2");
        String fingerprint = IdempotentRequestServiceImpl.fingerprint(two);

        assertEquals(64, fingerprint.length());
        assertEquals(fingerprint, IdempotentRequestServiceImpl.fingerprint(
                new ReservationRequest(two.materialId(), two.warehouseId(), two.projectId(), new BigDecimal("2.000000"), null)));
        assertNotEquals(fingerprint, IdempotentRequestServiceImpl.fingerprint(
                new ReservationRequest(two.materialId(), two.warehouseId(), two.projectId(), new BigDecimal("3"), null)));
        assertNotEquals(fingerprint, IdempotentRequestServiceImpl.fingerprint(
                new ReservationRequest(two.warehouseId(), two.materialId(), two.projectId(), new BigDecimal("2"), null)));
        assertNotEquals(
                IdempotentRequestServiceImpl.fingerprint(new StockMovementOutputRequest(
                        two.materialId(), two.warehouseId(), null, null, BigDecimal.ONE, null, "MO-1", "a|b")),
                IdempotentRequestServiceImpl.fingerprint(new StockMovementOutputRequest(
                        two.materialId(), two.warehouseId(), null, null, BigDecimal.ONE, null, "MO-1a", "|b")));
    }

    /**
     * La fecha no cuenta: un cliente que reintenta con la hora de cada intento manda la misma
     * petición, y sin esto recibía un 409. Lo demás sigue contando, también en la salida.
     */
    @Test
    void theRequestFingerprintLeavesTheDateOut() {
        ReservationRequest reservation = idempotentReservation("2");
        StockMovementOutputRequest output = new StockMovementOutputRequest(reservation.materialId(), reservation.warehouseId(),
                reservation.projectId(), null, BigDecimal.ONE, null, "MO-000001", "Maintenance order MO-000001");

        assertEquals(IdempotentRequestServiceImpl.fingerprint(reservation), IdempotentRequestServiceImpl.fingerprint(
                new ReservationRequest(reservation.materialId(), reservation.warehouseId(), reservation.projectId(),
                        reservation.quantity(), Instant.parse("2026-09-01T08:00:00Z"))));
        assertEquals(IdempotentRequestServiceImpl.fingerprint(output), IdempotentRequestServiceImpl.fingerprint(
                new StockMovementOutputRequest(output.materialId(), output.warehouseId(), output.projectId(), null,
                        output.quantity(), Instant.parse("2026-09-01T08:00:00Z"), output.externalReference(), output.notes())));
        assertNotEquals(IdempotentRequestServiceImpl.fingerprint(output), IdempotentRequestServiceImpl.fingerprint(
                new StockMovementOutputRequest(output.materialId(), output.warehouseId(), output.projectId(), null,
                        output.quantity(), null, output.externalReference(), "Maintenance order MO-000002")));
    }

    /**
     * La purga borra la fila de una clave caducada justo entre la reclamación que choca con ella y la
     * lectura: la clave vuelve a estar libre, se reclama otra vez y la petición se escribe como la
     * primera, en vez de fallar con un 500.
     */
    @Test
    void aKeyPurgedBetweenTheClaimAndTheReadIsClaimedAgain() {
        IdempotentRequestRepository repository = mock(IdempotentRequestRepository.class);
        IdempotentRequestServiceImpl service = new IdempotentRequestServiceImpl(repository, () -> Optional.of("mto-maintenance-svc"));
        ReservationRequest request = idempotentReservation("1");
        String fingerprint = IdempotentRequestServiceImpl.fingerprint(request);
        when(repository.claim("RESERVATION", "key-1", fingerprint, "mto-maintenance-svc")).thenReturn(0, 1);
        when(repository.findByOperationAndCreatedByAndIdempotencyKey(IdempotentOperation.RESERVATION, "mto-maintenance-svc", "key-1"))
                .thenReturn(Optional.empty());

        assertEquals(Optional.empty(), service.claim(IdempotentOperation.RESERVATION, "key-1", request));

        verify(repository, times(2)).claim("RESERVATION", "key-1", fingerprint, "mto-maintenance-svc");
    }

    /** Si ni reclamándola otra vez se puede leer, algo no cuadra: se dice, no se escribe a ciegas. */
    @Test
    void aKeyThatCannotBeClaimedNorReadIsAnError() {
        IdempotentRequestRepository repository = mock(IdempotentRequestRepository.class);
        IdempotentRequestServiceImpl service = new IdempotentRequestServiceImpl(repository, () -> Optional.of("mto-maintenance-svc"));
        when(repository.claim(eq("OUTPUT"), eq("key-1"), any(), eq("mto-maintenance-svc"))).thenReturn(0);
        when(repository.findByOperationAndCreatedByAndIdempotencyKey(IdempotentOperation.OUTPUT, "mto-maintenance-svc", "key-1"))
                .thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> service.claim(IdempotentOperation.OUTPUT, "key-1", idempotentReservation("1")));
    }

    /** Lote a lote, cada uno en su transacción, hasta que uno sale incompleto: no queda nada más viejo. */
    @Test
    void thePurgeForgetsOldKeysBatchByBatchUntilABatchComesOutShort() {
        IdempotentRequestRepository repository = mock(IdempotentRequestRepository.class);
        IdempotentRequestServiceImpl service = new IdempotentRequestServiceImpl(repository, Optional::empty);
        Instant cutoff = Instant.parse("2026-08-27T03:17:00Z");
        int batch = IdempotentRequestServiceImpl.PURGE_BATCH_SIZE;
        when(repository.deleteClaimedBefore(cutoff, batch)).thenReturn(batch, batch, 7);

        assertEquals(2 * batch + 7, service.purgeClaimedBefore(cutoff));
        verify(repository, times(3)).deleteClaimedBefore(cutoff, batch);

        when(repository.deleteClaimedBefore(cutoff, batch)).thenReturn(0);
        assertEquals(0, service.purgeClaimedBefore(cutoff));
    }

    /**
     * La tarea programada olvida lo usado antes de hace {@code retention}; un plazo vacío, cero o
     * negativo es el de por defecto, 30 días, porque olvidar una clave en el acto quitaría a los
     * clientes la protección que la clave promete.
     */
    @Test
    void theScheduledPurgeForgetsWhatIsOlderThanTheRetention() {
        IdempotentRequestService idempotentRequestService = mock(IdempotentRequestService.class);
        IdempotencyPurgeConfiguration purge = new IdempotencyPurgeConfiguration(idempotentRequestService,
                new IdempotencyProperties(Duration.ofDays(7)));

        Instant before = Instant.now();
        purge.purgeExpiredKeys();
        Instant after = Instant.now();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(idempotentRequestService).purgeClaimedBefore(cutoff.capture());
        assertFalse(cutoff.getValue().isBefore(before.minus(Duration.ofDays(7))));
        assertFalse(cutoff.getValue().isAfter(after.minus(Duration.ofDays(7))));
        for (Duration retention : Arrays.asList(null, Duration.ZERO, Duration.ofDays(-1))) {
            assertEquals(Duration.ofDays(30), new IdempotencyProperties(retention).retention(), String.valueOf(retention));
        }
    }

    /**
     * Encendida por defecto, una vez al día; con {@code app.idempotency.purge.enabled=false} no hay
     * ni configuración ni tarea, que es como corren los tests.
     */
    @Test
    void thePurgeIsScheduledOnceADayByDefaultAndNotAtAllWhenSwitchedOff() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(IdempotencyPurgeConfiguration.class)
                .withBean(IdempotentRequestService.class, () -> mock(IdempotentRequestService.class));

        runner.run(context -> {
            assertNull(context.getStartupFailure());
            List<String> crons = context.getBean(ScheduledTaskHolder.class).getScheduledTasks().stream()
                    .map(task -> ((CronTask) task.getTask()).getExpression())
                    .toList();
            assertEquals(List.of("0 17 3 * * *"), crons);
            assertEquals(Duration.ofDays(30), context.getBean(IdempotencyProperties.class).retention());
        });
        runner.withPropertyValues("app.idempotency.purge.enabled=false")
                .run(context -> assertTrue(context.getBeansOfType(IdempotencyPurgeConfiguration.class).isEmpty()));
    }

    /** Apuntar lo creado en una clave que esta escritura no reclamó dejaría el reintento sin respuesta. */
    @Test
    void recordingWhatWasCreatedFailsWhenThisWriteDidNotClaimTheKey() {
        IdempotentRequestRepository repository = mock(IdempotentRequestRepository.class);
        IdempotentRequestServiceImpl service = new IdempotentRequestServiceImpl(repository, () -> Optional.of("mto-maintenance-svc"));
        UUID created = UUID.randomUUID();
        when(repository.recordResource("OUTPUT", "key-1", "mto-maintenance-svc", created)).thenReturn(0);

        assertThrows(IllegalStateException.class, () -> service.complete(IdempotentOperation.OUTPUT, "key-1", created));
    }

    @Test
    void reservationEngineCreateActivatesReservationAndReservesAvailableBalance() {
        ReservationRepository reservationRepository = mock(ReservationRepository.class);
        MaterialRepository materialRepository = mock(MaterialRepository.class);
        WarehouseRepository warehouseRepository = mock(WarehouseRepository.class);
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        InventoryBalanceService inventoryBalanceService = mock(InventoryBalanceService.class);
        InventoryValidationService validationService = mock(InventoryValidationService.class);
        Material material = material("MAT-CRE");
        Warehouse warehouse = warehouse("WH-CRE");
        Project project = project("PRJ-CRE");
        Reservation requestedReservation = Reservation.builder()
                .material(material)
                .warehouse(warehouse)
                .project(project)
                .quantity(new BigDecimal("4.000000"))
                .build();
        when(materialRepository.findById(material.getId())).thenReturn(Optional.of(material));
        when(warehouseRepository.findById(warehouse.getId())).thenReturn(Optional.of(warehouse));
        when(projectRepository.findById(project.getId())).thenReturn(Optional.of(project));
        when(reservationRepository.save(requestedReservation)).thenReturn(requestedReservation);
        ReservationEngineImpl service = new ReservationEngineImpl(
                reservationRepository,
                materialRepository,
                warehouseRepository,
                projectRepository,
                inventoryBalanceService,
                validationService,
                new RecordingEventPublisher()
        );

        Reservation createdReservation = service.create(requestedReservation);

        assertEquals(ReservationStatus.ACTIVE, createdReservation.getStatus());
        verify(validationService).validatePositiveQuantity(new BigDecimal("4.000000"));
        verify(inventoryBalanceService).reserve(material.getId(), warehouse.getId(), new BigDecimal("4.000000"));
    }

    @Test
    void reservationEngineCancelReleasesReservedBalance() {
        ReservationRepository reservationRepository = mock(ReservationRepository.class);
        InventoryBalanceService inventoryBalanceService = mock(InventoryBalanceService.class);
        Reservation reservation = reservation();
        when(reservationRepository.findById(reservation.getId())).thenReturn(Optional.of(reservation));
        ReservationEngineImpl service = new ReservationEngineImpl(
                reservationRepository,
                mock(MaterialRepository.class),
                mock(WarehouseRepository.class),
                mock(ProjectRepository.class),
                inventoryBalanceService,
                mock(InventoryValidationService.class),
                new RecordingEventPublisher()
        );

        Reservation cancelledReservation = service.cancel(reservation.getId());

        assertEquals(ReservationStatus.CANCELLED, cancelledReservation.getStatus());
        assertNotNull(cancelledReservation.getReleasedAt());
        verify(inventoryBalanceService).releaseReserved(reservation.getMaterial().getId(), reservation.getWarehouse().getId(), reservation.getQuantity());
    }

    @Test
    void reservationEngineUpdateKeepsReservationMaterialAlignedWithTheMovedBalance() {
        ReservationRepository reservationRepository = mock(ReservationRepository.class);
        MaterialRepository materialRepository = mock(MaterialRepository.class);
        WarehouseRepository warehouseRepository = mock(WarehouseRepository.class);
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        InventoryBalanceService inventoryBalanceService = mock(InventoryBalanceService.class);
        Reservation existingReservation = reservation();
        Material previousMaterial = existingReservation.getMaterial();
        Warehouse warehouse = existingReservation.getWarehouse();
        Project project = existingReservation.getProject();
        Material newMaterial = material("MAT-SWAP");
        Reservation requestedReservation = Reservation.builder()
                .material(newMaterial)
                .warehouse(warehouse)
                .project(project)
                .quantity(new BigDecimal("3.000000"))
                .build();
        when(reservationRepository.findById(existingReservation.getId())).thenReturn(Optional.of(existingReservation));
        when(materialRepository.findById(newMaterial.getId())).thenReturn(Optional.of(newMaterial));
        when(warehouseRepository.findById(warehouse.getId())).thenReturn(Optional.of(warehouse));
        when(projectRepository.findById(project.getId())).thenReturn(Optional.of(project));
        ReservationEngineImpl service = new ReservationEngineImpl(
                reservationRepository,
                materialRepository,
                warehouseRepository,
                projectRepository,
                inventoryBalanceService,
                mock(InventoryValidationService.class),
                new RecordingEventPublisher()
        );

        Reservation updatedReservation = service.update(existingReservation.getId(), requestedReservation);

        assertSame(newMaterial, updatedReservation.getMaterial());
        verify(inventoryBalanceService).releaseReserved(previousMaterial.getId(), warehouse.getId(), new BigDecimal("2.000000"));
        verify(inventoryBalanceService).reserve(newMaterial.getId(), warehouse.getId(), new BigDecimal("3.000000"));
    }

    @Test
    void inventoryValidationRejectsDuplicateCodesButAcceptsTheOwningAggregate() {
        MaterialRepository materialRepository = mock(MaterialRepository.class);
        AssemblyRepository assemblyRepository = mock(AssemblyRepository.class);
        WarehouseRepository warehouseRepository = mock(WarehouseRepository.class);
        SupplierRepository supplierRepository = mock(SupplierRepository.class);
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        Material material = material("MAT-DUP");
        Assembly assembly = assembly("ASM-DUP");
        Warehouse warehouse = warehouse("WH-DUP");
        Supplier supplier = supplier("SUP-DUP");
        Project project = project("PRJ-DUP");
        when(materialRepository.findByCode("MAT-DUP")).thenReturn(Optional.of(material));
        when(assemblyRepository.findByCode("ASM-DUP")).thenReturn(Optional.of(assembly));
        when(warehouseRepository.findByCode("WH-DUP")).thenReturn(Optional.of(warehouse));
        when(supplierRepository.findByCode("SUP-DUP")).thenReturn(Optional.of(supplier));
        when(projectRepository.findByCode("PRJ-DUP")).thenReturn(Optional.of(project));
        InventoryValidationServiceImpl service = new InventoryValidationServiceImpl(
                materialRepository,
                assemblyRepository,
                warehouseRepository,
                supplierRepository,
                projectRepository
        );

        assertThrows(DuplicateCodeException.class, () -> service.validateMaterialCodeIsUnique("MAT-DUP", null));
        assertThrows(DuplicateCodeException.class, () -> service.validateAssemblyCodeIsUnique("ASM-DUP", null));
        assertThrows(DuplicateCodeException.class, () -> service.validateWarehouseCodeIsUnique("WH-DUP", null));
        assertThrows(DuplicateCodeException.class, () -> service.validateSupplierCodeIsUnique("SUP-DUP", null));
        assertThrows(DuplicateCodeException.class, () -> service.validateProjectCodeIsUnique("PRJ-DUP", null));

        service.validateMaterialCodeIsUnique("MAT-DUP", material.getId());
        service.validateAssemblyCodeIsUnique("ASM-DUP", assembly.getId());
        service.validateWarehouseCodeIsUnique("WH-DUP", warehouse.getId());
        service.validateSupplierCodeIsUnique("SUP-DUP", supplier.getId());
        service.validateProjectCodeIsUnique("PRJ-DUP", project.getId());
    }

    @Test
    void inventoryValidationRejectsInactiveMaterialWarehouseAndAssembly() {
        InventoryValidationServiceImpl service = new InventoryValidationServiceImpl(
                mock(MaterialRepository.class),
                mock(AssemblyRepository.class),
                mock(WarehouseRepository.class),
                mock(SupplierRepository.class),
                mock(ProjectRepository.class)
        );
        Material inactiveMaterial = Material.builder()
                .code("MAT-OFF")
                .name("Inactive material")
                .unitOfMeasure("unit")
                .minimumStockLevel(BigDecimal.ZERO)
                .active(false)
                .build();
        Warehouse inactiveWarehouse = Warehouse.builder().code("WH-OFF").name("Inactive warehouse").active(false).build();
        Assembly inactiveAssembly = Assembly.builder().code("ASM-OFF").name("Inactive assembly").active(false).build();

        assertThrows(ValidationException.class, () -> service.validateActive(inactiveMaterial));
        assertThrows(WarehouseException.class, () -> service.validateActive(inactiveWarehouse));
        assertThrows(AssemblyException.class, () -> service.validateActive(inactiveAssembly));
    }

    @Test
    void stockCalculationFlagsMaterialsBelowTheirMinimumStockLevel() {
        InventoryBalanceRepository inventoryBalanceRepository = mock(InventoryBalanceRepository.class);
        MaterialRepository materialRepository = mock(MaterialRepository.class);
        WarehouseRepository warehouseRepository = mock(WarehouseRepository.class);
        MaterialMapper materialMapper = mock(MaterialMapper.class);
        WarehouseMapper warehouseMapper = mock(WarehouseMapper.class);
        Material material = Material.builder()
                .code("MAT-MIN")
                .name("Material MAT-MIN")
                .unitOfMeasure("unit")
                .minimumStockLevel(new BigDecimal("10.000000"))
                .build();
        setId(material, UUID.randomUUID());
        Warehouse warehouse = warehouse("WH-MIN");
        when(materialRepository.findById(material.getId())).thenReturn(Optional.of(material));
        when(warehouseRepository.findById(warehouse.getId())).thenReturn(Optional.of(warehouse));
        when(inventoryBalanceRepository.calculatePhysicalQuantity(material.getId(), warehouse.getId(), BigDecimal.ZERO))
                .thenReturn(new BigDecimal("8.000000"));
        when(inventoryBalanceRepository.calculateReservedQuantity(material.getId(), warehouse.getId(), BigDecimal.ZERO))
                .thenReturn(new BigDecimal("3.000000"));
        when(inventoryBalanceRepository.calculateAvailableQuantity(material.getId(), warehouse.getId(), BigDecimal.ZERO))
                .thenReturn(new BigDecimal("5.000000"));
        when(materialMapper.toSummaryResponse(material)).thenReturn(materialSummary(material));
        when(warehouseMapper.toSummaryResponse(warehouse))
                .thenReturn(new WarehouseSummaryResponse(warehouse.getId(), warehouse.getCode(), warehouse.getName(), warehouse.getActive()));
        StockCalculationServiceImpl service = new StockCalculationServiceImpl(
                inventoryBalanceRepository,
                materialRepository,
                warehouseRepository,
                materialMapper,
                warehouseMapper
        );

        MaterialStockResponse response = service.calculateMaterialStock(material.getId(), warehouse.getId());

        assertEquals(new BigDecimal("8.000000"), response.onHandQuantity());
        assertEquals(new BigDecimal("3.000000"), response.activeReservedQuantity());
        assertEquals(new BigDecimal("5.000000"), response.availableQuantity());
        assertEquals(new BigDecimal("10.000000"), response.minimumStockLevel());
        assertTrue(response.lowStock());
        assertNotNull(response.calculatedAt());
    }

    @Test
    void stockCalculationReportsMissingMaterialAsNotFound() {
        MaterialRepository materialRepository = mock(MaterialRepository.class);
        UUID missingId = UUID.randomUUID();
        when(materialRepository.findById(missingId)).thenReturn(Optional.empty());
        StockCalculationServiceImpl service = new StockCalculationServiceImpl(
                mock(InventoryBalanceRepository.class),
                materialRepository,
                mock(WarehouseRepository.class),
                mock(MaterialMapper.class),
                mock(WarehouseMapper.class)
        );

        assertThrows(NotFoundException.class, () -> service.calculateMaterialStock(missingId, null));
    }

    @Test
    void inventoryBalanceServiceTruncatesAuditorToTheAuditColumnLength() {
        InventoryBalanceRepository inventoryBalanceRepository = mock(InventoryBalanceRepository.class);
        String longActor = "a".repeat(150);
        String truncatedActor = "a".repeat(100);
        AuditorAware<String> auditorAware = () -> Optional.of(longActor);
        UUID materialId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        BigDecimal quantity = new BigDecimal("1.000000");
        when(inventoryBalanceRepository.increasePhysical(materialId, warehouseId, quantity, truncatedActor)).thenReturn(1);
        InventoryBalanceServiceImpl service = balanceService(inventoryBalanceRepository, auditorAware);

        service.increasePhysical(materialId, warehouseId, quantity);

        verify(inventoryBalanceRepository).insertZeroBalanceIfMissing(materialId, warehouseId, truncatedActor);
        verify(inventoryBalanceRepository).increasePhysical(materialId, warehouseId, quantity, truncatedActor);
    }

    @Test
    void inventoryBalanceServiceRejectsNonPositiveQuantitiesWithoutTouchingTheProjection() {
        InventoryBalanceRepository inventoryBalanceRepository = mock(InventoryBalanceRepository.class);
        UUID materialId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        InventoryBalanceServiceImpl service = balanceService(inventoryBalanceRepository, () -> Optional.of("test-user"));

        assertThrows(ValidationException.class, () -> service.increasePhysical(materialId, warehouseId, BigDecimal.ZERO));
        assertThrows(ValidationException.class, () -> service.decreasePhysicalAndAvailable(materialId, warehouseId, new BigDecimal("-1.000000")));
        assertThrows(ValidationException.class, () -> service.reserve(materialId, warehouseId, null));
        assertThrows(ValidationException.class, () -> service.releaseReserved(materialId, warehouseId, BigDecimal.ZERO));
        assertThrows(ValidationException.class, () -> service.consumeReserved(materialId, warehouseId, null));
        verifyNoInteractions(inventoryBalanceRepository);
    }

    @Test
    void inventoryBalanceServiceReportsFailedProjectionUpdatesAsReservationErrors() {
        InventoryBalanceRepository inventoryBalanceRepository = mock(InventoryBalanceRepository.class);
        UUID materialId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        BigDecimal quantity = new BigDecimal("2.000000");
        when(inventoryBalanceRepository.increasePhysical(materialId, warehouseId, quantity, "system")).thenReturn(0);
        when(inventoryBalanceRepository.releaseReserved(materialId, warehouseId, quantity, "system")).thenReturn(0);
        when(inventoryBalanceRepository.consumeReserved(materialId, warehouseId, quantity, "system")).thenReturn(0);
        InventoryBalanceServiceImpl service = balanceService(inventoryBalanceRepository, Optional::empty);

        assertThrows(ReservationException.class, () -> service.increasePhysical(materialId, warehouseId, quantity));
        assertThrows(ReservationException.class, () -> service.releaseReserved(materialId, warehouseId, quantity));
        assertThrows(ReservationException.class, () -> service.consumeReserved(materialId, warehouseId, quantity));
    }

    @Test
    void transferStopsBeforeWritingMovementsWhenSourceStockIsInsufficient() {
        MaterialRepository materialRepository = mock(MaterialRepository.class);
        WarehouseRepository warehouseRepository = mock(WarehouseRepository.class);
        StockMovementRepository stockMovementRepository = mock(StockMovementRepository.class);
        InventoryBalanceService inventoryBalanceService = mock(InventoryBalanceService.class);
        Material material = material("MAT-TRF-LOW");
        Warehouse sourceWarehouse = warehouse("WH-SRC-LOW");
        Warehouse targetWarehouse = warehouse("WH-DST-LOW");
        StockMovementTransferRequest request = new StockMovementTransferRequest(
                material.getId(),
                sourceWarehouse.getId(),
                targetWarehouse.getId(),
                new BigDecimal("9.000000"),
                null,
                "TRF-LOW",
                null
        );
        when(materialRepository.findById(material.getId())).thenReturn(Optional.of(material));
        when(warehouseRepository.findById(sourceWarehouse.getId())).thenReturn(Optional.of(sourceWarehouse));
        when(warehouseRepository.findById(targetWarehouse.getId())).thenReturn(Optional.of(targetWarehouse));
        doThrow(new InsufficientStockException(material.getId(), sourceWarehouse.getId(), request.quantity(), BigDecimal.ZERO))
                .when(inventoryBalanceService).transfer(material.getId(), sourceWarehouse.getId(), targetWarehouse.getId(), request.quantity());
        TransferServiceImpl service = new TransferServiceImpl(
                materialRepository,
                warehouseRepository,
                stockMovementRepository,
                mock(StockMovementMapper.class),
                inventoryBalanceService,
                mock(InventoryValidationService.class)
        );

        assertThrows(InsufficientStockException.class, () -> service.transfer(request));
        verify(inventoryBalanceService, never()).increasePhysical(any(), any(), any());
        verify(stockMovementRepository, never()).save(any(StockMovement.class));
    }

    @Test
    void materialServiceRejectsDuplicateCodesAndMissingMaterials() {
        MaterialRepository materialRepository = mock(MaterialRepository.class);
        InventoryValidationService validationService = mock(InventoryValidationService.class);
        MaterialRequest createRequest = new MaterialRequest("MAT-DUP", "Copper wire", "m", BigDecimal.ZERO);
        MaterialUpdateRequest updateRequest = new MaterialUpdateRequest("MAT-1", "Copper wire", "m", BigDecimal.ZERO, true);
        UUID missingId = UUID.randomUUID();
        doThrow(new DuplicateCodeException("Material", "MAT-DUP"))
                .when(validationService).validateMaterialCodeIsUnique("MAT-DUP", null);
        when(materialRepository.findById(missingId)).thenReturn(Optional.empty());
        MaterialServiceImpl service = new MaterialServiceImpl(
                materialRepository,
                mock(MaterialMapper.class),
                mock(EntityAuditService.class),
                validationService,
                mock(StockCalculationService.class),
                mock(CacheInvalidator.class)
        );

        assertThrows(DuplicateCodeException.class, () -> service.create(createRequest));
        assertThrows(NotFoundException.class, () -> service.update(missingId, updateRequest));
        verify(materialRepository, never()).save(any(Material.class));
    }

    @Test
    void assemblyServiceAttachesManagedComponentMaterialsToBothSidesOfTheBom() {
        AssemblyRepository assemblyRepository = mock(AssemblyRepository.class);
        MaterialRepository materialRepository = mock(MaterialRepository.class);
        AssemblyMapper assemblyMapper = mock(AssemblyMapper.class);
        InventoryValidationService validationService = mock(InventoryValidationService.class);
        Material managedMaterial = material("MAT-BOM");
        Material materialReference = Material.builder()
                .code("MAT-BOM")
                .name("Material MAT-BOM")
                .unitOfMeasure("unit")
                .minimumStockLevel(BigDecimal.ZERO)
                .build();
        setId(materialReference, managedMaterial.getId());
        AssemblyRequest request = new AssemblyRequest(
                "ASM-BOM",
                "Section",
                List.of(new AssemblyComponentRequest(managedMaterial.getId(), new BigDecimal("2.000000")))
        );
        Assembly assembly = Assembly.builder().code("ASM-BOM").name("Section").build();
        assembly.addComponent(component(materialReference, "2.000000"));
        when(assemblyMapper.toEntity(request)).thenReturn(assembly);
        when(materialRepository.findById(managedMaterial.getId())).thenReturn(Optional.of(managedMaterial));
        when(assemblyRepository.save(assembly)).thenReturn(assembly);
        AssemblyServiceImpl service = new AssemblyServiceImpl(
                assemblyRepository,
                materialRepository,
                assemblyMapper,
                mock(EntityAuditService.class),
                validationService,
                mock(BOMCalculationService.class),
                mock(CacheInvalidator.class)
        );

        service.create(request);

        AssemblyComponent storedComponent = assembly.getComponents().getFirst();
        assertSame(managedMaterial, storedComponent.getMaterial());
        assertSame(assembly, storedComponent.getAssembly());
        verify(validationService).validateActive(managedMaterial);
        verify(validationService).validateAssemblyHasComponents(assembly);
    }

    // -----------------------------------------------------------------------------------------
    // Inbox: idempotencia del consumo
    // -----------------------------------------------------------------------------------------

    /**
     * Primera entrega: se registra, se reclama -que es lo que incrementa el contador de intentos- se
     * ejecuta el trabajo una sola vez y se marca como aplicado. Que el contador quede en 1 y que un
     * FAILED se pueda reclamar de nuevo lo comprueba InboxMessageRepositoryDataJpaTest contra
     * PostgreSQL, porque son garantias del SQL y no del servicio.
     */
    @Test
    void inboxRunsTheWorkOnceAndMarksTheMessageAsProcessed() {
        InboxMessageRepository inboxMessageRepository = mock(InboxMessageRepository.class);
        when(inboxMessageRepository.claimForProcessing(MESSAGE_ID, SOURCE_SERVICE)).thenReturn(1);
        when(inboxMessageRepository.markProcessed(MESSAGE_ID, SOURCE_SERVICE)).thenReturn(1);
        InboxMessageServiceImpl service = new InboxMessageServiceImpl(inboxMessageRepository);
        AtomicInteger executions = new AtomicInteger();

        InboxProcessingResult result = service.process(inboxCommand(), executions::incrementAndGet);

        assertEquals(InboxProcessingResult.PROCESSED, result);
        assertEquals(1, executions.get());
        verify(inboxMessageRepository).insertIfMissing(eq(MESSAGE_ID), eq(SOURCE_SERVICE), any(), any(), any(),
                any(), any(), any(), any(), eq(PAYLOAD));
        verify(inboxMessageRepository).claimForProcessing(MESSAGE_ID, SOURCE_SERVICE);
        verify(inboxMessageRepository).markProcessed(MESSAGE_ID, SOURCE_SERVICE);
    }

    /**
     * La reclamacion no toca las filas ya aplicadas, asi que devolver 0 filas ES la senal de
     * duplicado. Se decide con el recuento de un update atomico y no con un "existe?" previo,
     * porque entre esa lectura y la escritura caben dos entregas simultaneas.
     */
    @Test
    void inboxSkipsTheWorkWhenTheMessageWasAlreadyApplied() {
        InboxMessageRepository inboxMessageRepository = mock(InboxMessageRepository.class);
        when(inboxMessageRepository.claimForProcessing(MESSAGE_ID, SOURCE_SERVICE)).thenReturn(0);
        InboxMessageServiceImpl service = new InboxMessageServiceImpl(inboxMessageRepository);
        AtomicInteger executions = new AtomicInteger();

        InboxProcessingResult result = service.process(inboxCommand(), executions::incrementAndGet);

        assertEquals(InboxProcessingResult.DUPLICATE_SKIPPED, result);
        assertEquals(0, executions.get());
        verify(inboxMessageRepository, never()).markProcessed(any(), any());
    }

    /**
     * Marcar como aplicado un mensaje cuyo trabajo fallo dejaria el evento perdido para siempre: no
     * se reintentaria nunca porque el inbox lo daria por hecho.
     */
    @Test
    void inboxDoesNotMarkAsProcessedWhenTheWorkFails() {
        InboxMessageRepository inboxMessageRepository = mock(InboxMessageRepository.class);
        when(inboxMessageRepository.claimForProcessing(MESSAGE_ID, SOURCE_SERVICE)).thenReturn(1);
        InboxMessageServiceImpl service = new InboxMessageServiceImpl(inboxMessageRepository);

        assertThrows(IllegalStateException.class, () -> service.process(inboxCommand(), () -> {
            throw new IllegalStateException("database is down");
        }));

        verify(inboxMessageRepository, never()).markProcessed(any(), any());
    }

    /** Sin identificador estable no hay idempotencia que garantizar. */
    @Test
    void inboxRejectsACommandWithoutIdempotencyKey() {
        InboxMessageServiceImpl service = new InboxMessageServiceImpl(mock(InboxMessageRepository.class));
        InboxMessageCommand withoutKey = new InboxMessageCommand(
                " ", SOURCE_SERVICE, null, null, null, null, null, null, null, PAYLOAD, 7L);

        assertThrows(ValidationException.class, () -> service.process(withoutKey, () -> { }));
    }

    /** El motivo se recorta: el mensaje de una excepcion puede arrastrar un payload entero. */
    @Test
    void inboxTruncatesAVeryLongFailureReason() {
        InboxMessageRepository inboxMessageRepository = mock(InboxMessageRepository.class);
        InboxMessageServiceImpl service = new InboxMessageServiceImpl(inboxMessageRepository);
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);

        service.recordFailure(inboxCommand(), new IllegalStateException("x".repeat(5_000)));

        verify(inboxMessageRepository).recordFailure(eq(MESSAGE_ID), eq(SOURCE_SERVICE), any(), any(), any(),
                any(), any(), any(), any(), eq(PAYLOAD), reason.capture());
        assertEquals(2_000, reason.getValue().length());
    }

    // -----------------------------------------------------------------------------------------
    // IdempotentMasterDataEventProcessor
    // -----------------------------------------------------------------------------------------

    /**
     * El manejador solo se ejecuta desde dentro del inbox. Con un inbox que no invoca el trabajo
     * -lo que hace ante un duplicado- el manejador no llega a ejecutarse nunca.
     */
    @Test
    void masterDataHandlerOnlyRunsThroughTheInbox() {
        InboxMessageService inboxMessageService = mock(InboxMessageService.class);
        when(inboxMessageService.process(any(), any())).thenReturn(InboxProcessingResult.DUPLICATE_SKIPPED);
        AtomicInteger executions = new AtomicInteger();
        MasterDataEventHandler handler = (message, context) -> executions.incrementAndGet();

        InboxProcessingResult result = new IdempotentMasterDataEventProcessor(inboxMessageService, handler)
                .process(inboxCommand(), masterDataMessage(MasterDataEntityNames.STATION, MasterDataOperation.UPDATED));

        assertEquals(InboxProcessingResult.DUPLICATE_SKIPPED, result);
        assertEquals(0, executions.get());
    }

    /**
     * El estado fallido se escribe DESPUES de que la transaccion del intento haya terminado, no
     * desde dentro: esa transaccion tiene bloqueada la fila hasta revertir, y una transaccion nueva
     * que la tocase se quedaria esperando a otra que a su vez espera a que ella devuelva.
     */
    @Test
    void masterDataFailureIsRecordedAfterTheAttemptAndRethrownForTheBroker() {
        InboxMessageService inboxMessageService = mock(InboxMessageService.class);
        IllegalStateException failure = new IllegalStateException("database is down");
        when(inboxMessageService.process(any(), any())).thenThrow(failure);
        IdempotentMasterDataEventProcessor processor = new IdempotentMasterDataEventProcessor(
                inboxMessageService, new DispatchingMasterDataEventHandler(List.of()));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> processor.process(inboxCommand(),
                        masterDataMessage(MasterDataEntityNames.STATION, MasterDataOperation.UPDATED)));

        assertSame(failure, thrown);
        InOrder order = inOrder(inboxMessageService);
        order.verify(inboxMessageService).process(any(), any());
        order.verify(inboxMessageService).recordFailure(any(), eq(failure));
    }

    private static InboxMessageCommand inboxCommand() {
        return new InboxMessageCommand(MESSAGE_ID, SOURCE_SERVICE, "MASTER_DATA_STATION_UPDATED", "station",
                "42", "mto.master-data.exchange", "mto.master-data.station.updated",
                "mto.stock.master-data.queue", "hash", PAYLOAD, 7L);
    }

    // -----------------------------------------------------------------------------------------
    // DispatchingMasterDataEventHandler
    // -----------------------------------------------------------------------------------------

    @Test
    void masterDataChangeIsRoutedToTheHandlerOfItsEntity() {
        RecordingEntityHandler executionPackages = new RecordingEntityHandler(MasterDataEntityNames.EXECUTION_PACKAGE);
        RecordingEntityHandler stations = new RecordingEntityHandler(MasterDataEntityNames.STATION);
        MasterDataEventHandler dispatcher =
                new DispatchingMasterDataEventHandler(List.of(executionPackages, stations));

        dispatcher.handle(masterDataMessage(MasterDataEntityNames.STATION, MasterDataOperation.UPDATED), CONTEXT);

        assertEquals(List.of("onUpdated"), stations.calls);
        assertTrue(executionPackages.calls.isEmpty());
    }

    @Test
    void eachOperationReachesItsOwnMethod() {
        RecordingEntityHandler stations = new RecordingEntityHandler(MasterDataEntityNames.STATION);
        MasterDataEventHandler dispatcher = new DispatchingMasterDataEventHandler(List.of(stations));

        dispatcher.handle(masterDataMessage(MasterDataEntityNames.STATION, MasterDataOperation.CREATED), CONTEXT);
        dispatcher.handle(masterDataMessage(MasterDataEntityNames.STATION, MasterDataOperation.UPDATED), CONTEXT);
        dispatcher.handle(masterDataMessage(MasterDataEntityNames.STATION, MasterDataOperation.DELETED), CONTEXT);

        assertEquals(List.of("onCreated", "onUpdated", "onDeleted"), stations.calls);
    }

    /**
     * La cola esta enlazada a mto.master-data.# y llega todo lo que publica mto-configuration, que
     * hoy son ocho tipos de entidad y manana pueden ser mas. Tratar como error lo que no se atiende
     * mandaria a la DLQ la mayor parte del trafico normal y convertiria cada entidad nueva del
     * emisor en una averia aqui.
     */
    @Test
    void changesOfUnhandledEntitiesAreIgnoredInsteadOfFailing() {
        RecordingEntityHandler stations = new RecordingEntityHandler(MasterDataEntityNames.STATION);
        MasterDataEventHandler dispatcher = new DispatchingMasterDataEventHandler(List.of(stations));

        assertDoesNotThrow(() -> dispatcher.handle(
                masterDataMessage(MasterDataEntityNames.CANTILEVER, MasterDataOperation.CREATED), CONTEXT));

        assertTrue(stations.calls.isEmpty());
    }

    /** Sin ningun manejador registrado el consumo sigue funcionando: se registra y no se hace nada. */
    @Test
    void withoutAnyRegisteredHandlerEveryChangeIsSimplyIgnored() {
        MasterDataEventHandler dispatcher = new DispatchingMasterDataEventHandler(List.of());

        assertDoesNotThrow(() -> dispatcher.handle(
                masterDataMessage(MasterDataEntityNames.EXECUTION_PACKAGE, MasterDataOperation.CREATED), CONTEXT));
    }

    /** Un manejador que declarase "Execution-Package" no se ejecutaria nunca y nada lo delataria. */
    @Test
    void entityNameMatchingIgnoresCaseAndSurroundingSpaces() {
        RecordingEntityHandler packages = new RecordingEntityHandler("  Execution-Package ");
        MasterDataEventHandler dispatcher = new DispatchingMasterDataEventHandler(List.of(packages));

        dispatcher.handle(masterDataMessage(MasterDataEntityNames.EXECUTION_PACKAGE, MasterDataOperation.CREATED), CONTEXT);

        assertEquals(List.of("onCreated"), packages.calls);
    }

    /**
     * Cual de los dos se ejecutase dependeria del orden de escaneo del classpath: una diferencia de
     * comportamiento entre dos arranques del mismo binario.
     */
    @Test
    void twoHandlersForTheSameEntityStopTheApplicationFromStarting() {
        List<MasterDataEntityHandler> duplicated = List.of(
                new RecordingEntityHandler(MasterDataEntityNames.STATION),
                new RecordingEntityHandler(MasterDataEntityNames.STATION));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new DispatchingMasterDataEventHandler(duplicated));

        assertTrue(exception.getMessage().contains(MasterDataEntityNames.STATION));
    }

    @Test
    void aHandlerWithoutEntityNameStopsTheApplicationFromStarting() {
        List<MasterDataEntityHandler> blank = List.of(new RecordingEntityHandler(" "));

        assertThrows(IllegalStateException.class, () -> new DispatchingMasterDataEventHandler(blank));
    }

    /** Un manejador puede atender solo las operaciones que le interesen. */
    @Test
    void unimplementedOperationsDoNothingByDefault() {
        MasterDataEntityHandler onlyDeletions = new MasterDataEntityHandler() {
            @Override
            public String entityName() {
                return MasterDataEntityNames.TRACK;
            }
        };
        MasterDataEventHandler dispatcher = new DispatchingMasterDataEventHandler(List.of(onlyDeletions));

        assertDoesNotThrow(() -> dispatcher.handle(
                masterDataMessage(MasterDataEntityNames.TRACK, MasterDataOperation.CREATED), CONTEXT));
    }

    /** values es un mapa abierto del emisor: puede llegar vacio o directamente ausente. */
    @Test
    void masterDataChangeWithoutValuesIsRoutedWithoutFailing() {
        RecordingEntityHandler stations = new RecordingEntityHandler(MasterDataEntityNames.STATION);
        MasterDataEventHandler dispatcher = new DispatchingMasterDataEventHandler(List.of(stations));
        MasterDataChangedMessage withoutValues = new MasterDataChangedMessage(
                UUID.randomUUID(), "station-42", "mto-configuration", Instant.now(),
                "MASTER_DATA_STATION_DELETED",
                new MasterDataChangedEvent(MasterDataEntityNames.STATION, "42", MasterDataOperation.DELETED, null),
                "hash");

        assertDoesNotThrow(() -> dispatcher.handle(withoutValues, CONTEXT));

        assertEquals(List.of("onDeleted"), stations.calls);
    }

    // -----------------------------------------------------------------------------------------
    // ExecutionPackageMasterDataHandler
    // -----------------------------------------------------------------------------------------

    @Test
    void executionPackageHandlerClaimsItsEntity() {
        assertEquals(MasterDataEntityNames.EXECUTION_PACKAGE,
                new ExecutionPackageMasterDataHandler(mock(ProjectRepository.class), mock(CacheInvalidator.class)).entityName());
    }

    /**
     * El codigo se deriva del identificador de origen y no del nombre: project.code es obligatorio y
     * unico, el paquete de ejecucion no publica ninguno, y el nombre si cambia entre entregas.
     */
    @Test
    void newExecutionPackageCreatesAProjectCodedAfterItsSourceId() {
        ProjectRepository projectRepository = mock(ProjectRepository.class);

        new ExecutionPackageMasterDataHandler(projectRepository, mock(CacheInvalidator.class)).onCreated(executionPackage("42",
                Map.of("id", 42, "name", "Tramo Sants-Sagrera", "enabled", true)), CONTEXT);

        verify(projectRepository).upsertFromMasterData(
                "mto-configuration", "42", "EP-42", "Tramo Sants-Sagrera", true, 7L);
    }

    /**
     * Alta y modificacion hacen lo mismo. La entrega es at-least-once: tratar el alta como un
     * insertar-o-fallar convertiria en averia una reentrega perfectamente normal.
     */
    @Test
    void updatedExecutionPackageTakesTheSamePathAsACreatedOne() {
        ProjectRepository projectRepository = mock(ProjectRepository.class);

        new ExecutionPackageMasterDataHandler(projectRepository, mock(CacheInvalidator.class)).onUpdated(executionPackage("42",
                Map.of("id", 42, "name", "Tramo Sants-Sagrera (revisado)", "enabled", false)), CONTEXT);

        verify(projectRepository).upsertFromMasterData(
                "mto-configuration", "42", "EP-42", "Tramo Sants-Sagrera (revisado)", false, 7L);
    }

    /**
     * Nunca se borra: reservation y stock_movement apuntan a project con on delete restrict, asi que
     * un proyecto con historial no se podria borrar aunque se quisiera.
     */
    @Test
    void deletedExecutionPackageDeactivatesTheProjectInsteadOfRemovingIt() {
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        when(projectRepository.deactivateFromMasterData("mto-configuration", "42", 7L)).thenReturn(1);

        new ExecutionPackageMasterDataHandler(projectRepository, mock(CacheInvalidator.class)).onDeleted(executionPackage("42", Map.of()), CONTEXT);

        verify(projectRepository).deactivateFromMasterData("mto-configuration", "42", 7L);
        verify(projectRepository, never()).delete(any(Project.class));
        verify(projectRepository, never()).upsertFromMasterData(any(), any(), any(), any(), anyBoolean(), any());
    }

    /** Puede llegar la baja de un paquete que este servicio nunca vio; no es un error. */
    @Test
    void deletingAnExecutionPackageWithNoMatchingProjectIsNotAnError() {
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        when(projectRepository.deactivateFromMasterData("mto-configuration", "99", 7L)).thenReturn(0);

        assertDoesNotThrow(() -> new ExecutionPackageMasterDataHandler(projectRepository, mock(CacheInvalidator.class))
                .onDeleted(executionPackage("99", Map.of()), CONTEXT));
    }

    /** Un campo que falte no puede desactivar un proyecto vivo: el valor por defecto en origen es true. */
    @Test
    void executionPackageWithoutEnabledFlagIsTreatedAsActive() {
        ProjectRepository projectRepository = mock(ProjectRepository.class);

        new ExecutionPackageMasterDataHandler(projectRepository, mock(CacheInvalidator.class)).onCreated(executionPackage("42",
                Map.of("id", 42, "name", "Tramo Sants-Sagrera")), CONTEXT);

        verify(projectRepository).upsertFromMasterData(any(), any(), any(), any(), eq(true), any());
    }

    /** Un proyecto llamado "EP-42" en la pantalla de reservas no lo reconoce nadie. */
    @Test
    void executionPackageWithoutNameIsRejectedInsteadOfNamedAfterItsCode() {
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        ExecutionPackageMasterDataHandler handler = new ExecutionPackageMasterDataHandler(projectRepository, mock(CacheInvalidator.class));
        MasterDataChangedMessage withoutName = executionPackage("42", Map.of("id", 42, "enabled", true));

        assertThrows(ValidationException.class, () -> handler.onCreated(withoutName, CONTEXT));

        verifyNoInteractions(projectRepository);
    }

    @Test
    void executionPackageWithoutEntityIdIsRejected() {
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        ExecutionPackageMasterDataHandler handler = new ExecutionPackageMasterDataHandler(projectRepository, mock(CacheInvalidator.class));
        MasterDataChangedMessage withoutId = executionPackage(" ", Map.of("name", "Tramo"));

        assertThrows(ValidationException.class, () -> handler.onCreated(withoutId, CONTEXT));

        verifyNoInteractions(projectRepository);
    }

    /**
     * Un proyecto sincronizado lo pisa el siguiente evento de datos maestros: el PUT lo rechaza con
     * su origen en vez de aceptar un cambio efimero.
     */
    @Test
    void updatingASynchronizedProjectIsRefusedWithItsSource() {
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        UUID id = UUID.randomUUID();
        Project project = Project.builder().code("EP-42").name("Tramo").active(true)
                .sourceService("mto-configuration").sourceEntityId("42").build();
        when(projectRepository.findById(id)).thenReturn(Optional.of(project));
        ProjectServiceImpl service = new ProjectServiceImpl(projectRepository, projectMapper, mock(EntityAuditService.class), mock(InventoryValidationService.class), mock(CacheInvalidator.class));

        ProjectException exception = assertThrows(ProjectException.class,
                () -> service.update(id, new ProjectUpdateRequest("EP-42", "Otro nombre", false)));

        assertTrue(exception.getMessage().contains("EP-42"));
        assertTrue(exception.getMessage().contains("mto-configuration"));
        verify(projectMapper, never()).updateEntity(any(), any());
    }

    /**
     * Lo que se lee en la DLQ ante un choque de codigos es una violacion de restriccion sin
     * contexto: cuesta una tarde averiguar de donde salia.
     */
    @Test
    void aCodeAlreadyTakenByAnotherProjectFailsWithAnExplanation() {
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        when(projectRepository.upsertFromMasterData(any(), any(), any(), any(), anyBoolean(), any()))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates uq_project_code"));
        ExecutionPackageMasterDataHandler handler = new ExecutionPackageMasterDataHandler(projectRepository, mock(CacheInvalidator.class));
        MasterDataChangedMessage message = executionPackage("42", Map.of("name", "Tramo"));

        ValidationException exception = assertThrows(ValidationException.class, () -> handler.onCreated(message, CONTEXT));

        assertTrue(exception.getMessage().contains("EP-42"));
    }

    /** Solo se copia lo que un proyecto de mto-stock sabe guardar; lo demas queda en el inbox. */
    @Test
    void fieldsWithNoPlaceInTheProjectAreDropped() {
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", 42);
        values.put("name", "Tramo Sants-Sagrera");
        values.put("enabled", true);
        values.put("initialPackage", true);
        values.put("length", 12_500L);
        values.put("startDate", "2026-01-15");
        values.put("endDate", "2026-12-20");
        values.put("company", Map.of("id", 7, "code", "ACME", "name", "Acme Rail"));
        values.put("tracks", List.of(Map.of("id", 1, "name", "V1")));
        values.put("stations", List.of(Map.of("id", 2, "name", "Sants")));

        assertDoesNotThrow(() -> new ExecutionPackageMasterDataHandler(projectRepository, mock(CacheInvalidator.class))
                .onCreated(executionPackage("42", values), CONTEXT));

        verify(projectRepository).upsertFromMasterData(
                "mto-configuration", "42", "EP-42", "Tramo Sants-Sagrera", true, 7L);
    }

    /**
     * La sentencia no toca la fila cuando el evento viene por detras de lo ya aplicado, y eso llega
     * como 0 filas. No es un fallo -no puede serlo: mandar a la DLQ un evento viejo lo unico que
     * consigue es llenarla de trafico normal.
     */
    @Test
    void anExecutionPackageChangeThatArrivesLateIsDiscardedWithoutFailing() {
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        when(projectRepository.upsertFromMasterData(any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(0);

        assertDoesNotThrow(() -> new ExecutionPackageMasterDataHandler(projectRepository, mock(CacheInvalidator.class))
                .onUpdated(executionPackage("42", Map.of("name", "Nombre viejo")),
                        new MasterDataEventContext(3L)));
    }

    @Test
    void aLateDeletionIsDiscardedWithoutFailing() {
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        when(projectRepository.deactivateFromMasterData(any(), any(), any())).thenReturn(0);
        when(projectRepository.findBySourceServiceAndSourceEntityId("mto-configuration", "42"))
                .thenReturn(Optional.of(Project.builder().code("EP-42").name("Tramo").build()));

        assertDoesNotThrow(() -> new ExecutionPackageMasterDataHandler(projectRepository, mock(CacheInvalidator.class))
                .onDeleted(executionPackage("42", Map.of()), new MasterDataEventContext(3L)));
    }

    /**
     * Sin numero de secuencia no se puede saber que el evento sea viejo, y rechazarlo dejaria el
     * servicio sin consumir nada si el emisor dejara de enviar la cabecera: se aplica.
     */
    @Test
    void anEventWithoutSequenceNumberIsStillApplied() {
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        when(projectRepository.upsertFromMasterData(any(), any(), any(), any(), anyBoolean(), isNull()))
                .thenReturn(1);

        new ExecutionPackageMasterDataHandler(projectRepository, mock(CacheInvalidator.class))
                .onCreated(executionPackage("42", Map.of("name", "Tramo")), new MasterDataEventContext(null));

        verify(projectRepository).upsertFromMasterData(
                "mto-configuration", "42", "EP-42", "Tramo", true, null);
    }

    /**
     * La entrada del material cambiado se va, y con ella TODA la cache de conjuntos: AssemblyResponse
     * lleva dentro un MaterialSummaryResponse por linea de la BOM -codigo, nombre, unidad y si esta
     * activo-, asi que cambiar un material deja obsoleto cualquier conjunto que lo incluya. Desde
     * aqui no se sabe cuales son: la BOM se navega del conjunto al material, no al reves.
     */
    @Test
    void materialUpdateEvictsTheMaterialAndEveryCachedAssembly() {
        MaterialRepository materialRepository = mock(MaterialRepository.class);
        CacheInvalidator cacheInvalidator = mock(CacheInvalidator.class);
        Material material = material("MAT-1");
        when(materialRepository.findById(material.getId())).thenReturn(Optional.of(material));
        MaterialServiceImpl service = new MaterialServiceImpl(
                materialRepository,
                mock(MaterialMapper.class),
                mock(EntityAuditService.class),
                mock(InventoryValidationService.class),
                mock(StockCalculationService.class),
                cacheInvalidator
        );

        service.update(material.getId(), new MaterialUpdateRequest(
                "MAT-1", "Copper wire", "m", BigDecimal.ZERO, true));

        verify(cacheInvalidator).evictAfterCommit(CacheNames.MATERIALS, material.getId());
        verify(cacheInvalidator).evictAllAfterCommit(CacheNames.ASSEMBLIES);
    }

    /** Cada uno se lleva por delante solo la entrada que cambia, que es la unica que puede estar rancia. */
    @Test
    void catalogueUpdatesEvictTheEntryTheyChange() {
        WarehouseRepository warehouseRepository = mock(WarehouseRepository.class);
        SupplierRepository supplierRepository = mock(SupplierRepository.class);
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        CacheInvalidator cacheInvalidator = mock(CacheInvalidator.class);
        Warehouse warehouse = warehouse("WH-1");
        Supplier supplier = supplier("SUP-1");
        Project project = project("PRJ-1");
        when(warehouseRepository.findById(warehouse.getId())).thenReturn(Optional.of(warehouse));
        when(supplierRepository.findById(supplier.getId())).thenReturn(Optional.of(supplier));
        when(projectRepository.findById(project.getId())).thenReturn(Optional.of(project));

        new WarehouseServiceImpl(
                warehouseRepository,
                mock(WarehouseMapper.class),
                mock(EntityAuditService.class),
                mock(StockMovementMapper.class),
                mock(InventoryValidationService.class),
                mock(StockCalculationService.class),
                mock(TransferService.class),
                cacheInvalidator
        ).update(warehouse.getId(), new WarehouseUpdateRequest("WH-1", "Central", true));

        new SupplierServiceImpl(
                supplierRepository,
                mock(SupplierMapper.class),
                mock(EntityAuditService.class),
                mock(InventoryValidationService.class),
                cacheInvalidator
        ).update(supplier.getId(), new SupplierUpdateRequest("SUP-1", "Cables SA", true));

        new ProjectServiceImpl(
                projectRepository,
                mock(ProjectMapper.class),
                mock(EntityAuditService.class),
                mock(InventoryValidationService.class),
                cacheInvalidator
        ).update(project.getId(), new ProjectUpdateRequest("PRJ-1", "Tramo norte", true));

        verify(cacheInvalidator).evictAfterCommit(CacheNames.WAREHOUSES, warehouse.getId());
        verify(cacheInvalidator).evictAfterCommit(CacheNames.SUPPLIERS, supplier.getId());
        verify(cacheInvalidator).evictAfterCommit(CacheNames.PROJECTS, project.getId());
    }

    /** Un alta no tiene ninguna entrada que invalidar: el id todavia no existe. */
    @Test
    void catalogueCreationsEvictNothing() {
        MaterialRepository materialRepository = mock(MaterialRepository.class);
        MaterialMapper materialMapper = mock(MaterialMapper.class);
        CacheInvalidator cacheInvalidator = mock(CacheInvalidator.class);
        Material material = material("MAT-1");
        when(materialMapper.toEntity(any(MaterialRequest.class))).thenReturn(material);
        when(materialRepository.save(material)).thenReturn(material);

        new MaterialServiceImpl(
                materialRepository,
                materialMapper,
                mock(EntityAuditService.class),
                mock(InventoryValidationService.class),
                mock(StockCalculationService.class),
                cacheInvalidator
        ).create(new MaterialRequest("MAT-1", "Copper wire", "m", BigDecimal.ZERO));

        verifyNoInteractions(cacheInvalidator);
    }

    /**
     * Este handler es el segundo camino por el que cambia un project: escribe con SQL nativo sin
     * pasar por ProjectService, asi que si no invalidase por su cuenta un proyecto renombrado desde
     * mto-configuration se seguiria sirviendo viejo hasta que caducase el TTL.
     *
     * <p>Entera y no por clave porque el upsert devuelve cuantas filas toco, no el id.</p>
     */
    @Test
    void executionPackageChangeEvictsTheProjectCache() {
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        CacheInvalidator cacheInvalidator = mock(CacheInvalidator.class);
        when(projectRepository.upsertFromMasterData(any(), any(), any(), any(), anyBoolean(), any())).thenReturn(1);

        new ExecutionPackageMasterDataHandler(projectRepository, cacheInvalidator).onUpdated(executionPackage("42",
                Map.of("id", 42, "name", "Tramo Sants-Sagrera", "enabled", true)), CONTEXT);

        verify(cacheInvalidator).evictAllAfterCommit(CacheNames.PROJECTS);
    }

    /** La baja tambien cambia la fila -desactiva-, asi que tambien invalida. */
    @Test
    void executionPackageDeletionEvictsTheProjectCache() {
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        CacheInvalidator cacheInvalidator = mock(CacheInvalidator.class);
        when(projectRepository.deactivateFromMasterData("mto-configuration", "42", 7L)).thenReturn(1);

        new ExecutionPackageMasterDataHandler(projectRepository, cacheInvalidator)
                .onDeleted(executionPackage("42", Map.of()), CONTEXT);

        verify(cacheInvalidator).evictAllAfterCommit(CacheNames.PROJECTS);
    }

    /**
     * Un evento que llega por detras de lo ya aplicado no toca ninguna fila, y entonces tampoco hay
     * nada que invalidar: vaciar la cache ahi seria tirar entradas correctas por un cambio que la
     * marca de agua acaba de descartar.
     */
    @Test
    void executionPackageChangeThatTouchesNoRowLeavesTheProjectCacheAlone() {
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        CacheInvalidator cacheInvalidator = mock(CacheInvalidator.class);
        when(projectRepository.upsertFromMasterData(any(), any(), any(), any(), anyBoolean(), any())).thenReturn(0);
        when(projectRepository.deactivateFromMasterData(any(), any(), any())).thenReturn(0);
        ExecutionPackageMasterDataHandler handler =
                new ExecutionPackageMasterDataHandler(projectRepository, cacheInvalidator);

        handler.onUpdated(executionPackage("42", Map.of("id", 42, "name", "Tramo", "enabled", true)), CONTEXT);
        handler.onDeleted(executionPackage("42", Map.of()), CONTEXT);

        verify(cacheInvalidator, never()).evictAllAfterCommit(any());
    }

    private static MasterDataChangedMessage executionPackage(String entityId, Map<String, Object> values) {
        return new MasterDataChangedMessage(
                UUID.randomUUID(),
                "execution-package-" + entityId,
                "mto-configuration",
                Instant.now(),
                "MASTER_DATA_EXECUTION_PACKAGE_CREATED",
                new MasterDataChangedEvent(MasterDataEntityNames.EXECUTION_PACKAGE, entityId,
                        MasterDataOperation.CREATED, values),
                "hash");
    }

    // ----------------------------------------------------------------- own events (docs/06-messaging.md)

    @Test
    void anOutputThatCrossesBelowTheMinimumPublishesTheEventOnce() {
        InventoryBalanceRepository inventoryBalanceRepository = mock(InventoryBalanceRepository.class);
        MaterialRepository materialRepository = mock(MaterialRepository.class);
        RecordingEventPublisher events = new RecordingEventPublisher();
        Material material = material("MAT-MIN");
        material.setMinimumStockLevel(new BigDecimal("10.000000"));
        UUID warehouseId = UUID.randomUUID();
        when(materialRepository.findByIdForUpdate(material.getId())).thenReturn(Optional.of(material));
        when(inventoryBalanceRepository.decreasePhysicalAndAvailable(eq(material.getId()), eq(warehouseId), any(), eq("test-user"))).thenReturn(1);
        // El disponible total tras cada salida: 12 -> 8 cruza, 8 -> 5 ya estaba por debajo, 13 -> 12 sigue por encima.
        when(inventoryBalanceRepository.calculateAvailableQuantity(material.getId(), null, BigDecimal.ZERO))
                .thenReturn(new BigDecimal("8.000000"), new BigDecimal("5.000000"), new BigDecimal("12.000000"));
        InventoryBalanceServiceImpl service = new InventoryBalanceServiceImpl(inventoryBalanceRepository, materialRepository, events,
                () -> Optional.of("test-user"));

        service.decreasePhysicalAndAvailable(material.getId(), warehouseId, new BigDecimal("4.000000"));
        service.decreasePhysicalAndAvailable(material.getId(), warehouseId, new BigDecimal("3.000000"));
        service.decreasePhysicalAndAvailable(material.getId(), warehouseId, new BigDecimal("1.000000"));

        assertEquals(List.of("material.below-minimum"), events.names(), "solo al cruzar, no en cada salida por debajo");
        DomainEvent event = events.published.getFirst();
        assertEquals(material.getId().toString(), event.entityId());
        assertEquals("MAT-MIN", event.values().get("materialCode"));
        assertEquals("unit", event.values().get("unit"));
        assertEquals(new BigDecimal("10.000000"), event.values().get("minimumStockLevel"));
        assertEquals(new BigDecimal("12.000000"), event.values().get("availableBefore"));
        assertEquals(new BigDecimal("8.000000"), event.values().get("availableAfter"));
        assertEquals(new BigDecimal("4.000000"), event.values().get("quantity"));
        assertEquals("OUTPUT", event.values().get("operation"));
        assertEquals(warehouseId, event.values().get("warehouseId"));
        InOrder order = inOrder(materialRepository, inventoryBalanceRepository);
        order.verify(materialRepository).findByIdForUpdate(material.getId());
        order.verify(inventoryBalanceRepository).decreasePhysicalAndAvailable(eq(material.getId()), eq(warehouseId), any(), eq("test-user"));
    }

    @Test
    void aReservationThatCrossesBelowTheMinimumPublishesTheEventAndATransferNeverDoes() {
        InventoryBalanceRepository inventoryBalanceRepository = mock(InventoryBalanceRepository.class);
        MaterialRepository materialRepository = mock(MaterialRepository.class);
        RecordingEventPublisher events = new RecordingEventPublisher();
        Material material = material("MAT-RES-MIN");
        material.setMinimumStockLevel(new BigDecimal("10.000000"));
        UUID warehouseId = UUID.randomUUID();
        UUID otherWarehouseId = UUID.randomUUID();
        when(materialRepository.findByIdForUpdate(material.getId())).thenReturn(Optional.of(material));
        when(inventoryBalanceRepository.reserve(material.getId(), warehouseId, new BigDecimal("6.000000"), "system")).thenReturn(1);
        when(inventoryBalanceRepository.calculateAvailableQuantity(material.getId(), null, BigDecimal.ZERO)).thenReturn(new BigDecimal("4.000000"));
        when(inventoryBalanceRepository.decreasePhysicalAndAvailable(material.getId(), warehouseId, new BigDecimal("2.000000"), "system")).thenReturn(1);
        when(inventoryBalanceRepository.increasePhysical(material.getId(), otherWarehouseId, new BigDecimal("2.000000"), "system")).thenReturn(1);
        InventoryBalanceServiceImpl service = new InventoryBalanceServiceImpl(inventoryBalanceRepository, materialRepository, events, Optional::empty);

        service.reserve(material.getId(), warehouseId, new BigDecimal("6.000000"));
        service.transfer(material.getId(), warehouseId, otherWarehouseId, new BigDecimal("2.000000"));

        assertEquals(List.of("material.below-minimum"), events.names(), "una transferencia no cambia el total del material");
        assertEquals("RESERVATION", events.published.getFirst().values().get("operation"));
        verify(materialRepository, times(2)).findByIdForUpdate(material.getId());
        verify(inventoryBalanceRepository).insertZeroBalanceIfMissing(material.getId(), otherWarehouseId, "system");
        verify(inventoryBalanceRepository, times(1)).calculateAvailableQuantity(material.getId(), null, BigDecimal.ZERO);
    }

    @Test
    void aTransferWithoutStockAtTheSourceFailsBeforeTouchingTheTarget() {
        InventoryBalanceRepository inventoryBalanceRepository = mock(InventoryBalanceRepository.class);
        MaterialRepository materialRepository = mock(MaterialRepository.class);
        Material material = material("MAT-TRF-EMPTY");
        UUID sourceWarehouseId = UUID.randomUUID();
        UUID targetWarehouseId = UUID.randomUUID();
        when(materialRepository.findByIdForUpdate(material.getId())).thenReturn(Optional.of(material));
        when(inventoryBalanceRepository.decreasePhysicalAndAvailable(material.getId(), sourceWarehouseId, BigDecimal.ONE, "system")).thenReturn(0);
        when(inventoryBalanceRepository.calculateAvailableQuantity(material.getId(), sourceWarehouseId, BigDecimal.ZERO)).thenReturn(BigDecimal.ZERO);
        InventoryBalanceServiceImpl service = new InventoryBalanceServiceImpl(inventoryBalanceRepository, materialRepository,
                new RecordingEventPublisher(), Optional::empty);

        assertThrows(InsufficientStockException.class, () -> service.transfer(material.getId(), sourceWarehouseId, targetWarehouseId, BigDecimal.ONE));

        verify(inventoryBalanceRepository, never()).insertZeroBalanceIfMissing(any(), any(), any());
        verify(inventoryBalanceRepository, never()).increasePhysical(any(), any(), any(), any());
    }

    @Test
    void aMaterialWithoutAMinimumNeverSumsItsStockAndAnUnknownMaterialIsNotFound() {
        InventoryBalanceRepository inventoryBalanceRepository = mock(InventoryBalanceRepository.class);
        MaterialRepository materialRepository = mock(MaterialRepository.class);
        RecordingEventPublisher events = new RecordingEventPublisher();
        Material material = material("MAT-NO-MIN");
        UUID warehouseId = UUID.randomUUID();
        when(materialRepository.findByIdForUpdate(material.getId())).thenReturn(Optional.of(material));
        when(inventoryBalanceRepository.decreasePhysicalAndAvailable(material.getId(), warehouseId, BigDecimal.ONE, "system")).thenReturn(1);
        InventoryBalanceServiceImpl service = new InventoryBalanceServiceImpl(inventoryBalanceRepository, materialRepository, events, Optional::empty);

        service.decreasePhysicalAndAvailable(material.getId(), warehouseId, BigDecimal.ONE);

        assertTrue(events.published.isEmpty(), "un minimo de cero nunca se cruza");
        verify(inventoryBalanceRepository, never()).calculateAvailableQuantity(any(), any(), any());
        UUID unknown = UUID.randomUUID();
        assertThrows(NotFoundException.class, () -> service.reserve(unknown, warehouseId, BigDecimal.ONE));
    }

    @Test
    void cancellingAndReleasingAReservationPublishTheEventWithWhoCreatedIt() {
        ReservationRepository reservationRepository = mock(ReservationRepository.class);
        InventoryBalanceService inventoryBalanceService = mock(InventoryBalanceService.class);
        InventoryValidationService validationService = mock(InventoryValidationService.class);
        RecordingEventPublisher events = new RecordingEventPublisher();
        Reservation cancelled = reservation();
        cancelled.setCreatedBy("service-account-mto-maintenance-svc");
        Reservation released = reservation();
        released.setCreatedBy("almacen.operario");
        when(reservationRepository.findById(cancelled.getId())).thenReturn(Optional.of(cancelled));
        when(reservationRepository.findById(released.getId())).thenReturn(Optional.of(released));
        ReservationEngineImpl service = new ReservationEngineImpl(reservationRepository, mock(MaterialRepository.class),
                mock(WarehouseRepository.class), mock(ProjectRepository.class), inventoryBalanceService, validationService, events);

        service.cancel(cancelled.getId());
        service.release(released.getId());

        assertEquals(List.of("reservation.cancelled", "reservation.released"), events.names());
        DomainEvent event = events.published.getFirst();
        assertEquals(cancelled.getId().toString(), event.entityId());
        assertEquals("service-account-mto-maintenance-svc", event.values().get("createdBy"), "quien la creo: la regla sabe si la toca otro");
        assertEquals("CANCELLED", event.values().get("status"));
        assertEquals("MAT-RES", event.values().get("materialCode"));
        assertEquals("WH-RES", event.values().get("warehouseCode"));
        assertEquals("PRJ-001", event.values().get("projectCode"));
        assertEquals(new BigDecimal("2.000000"), event.values().get("quantity"));
        assertNotNull(event.values().get("releasedAt"));
        assertEquals("RELEASED", events.published.get(1).values().get("status"));
        assertEquals("almacen.operario", events.published.get(1).values().get("createdBy"));
    }

    @Test
    void anAdjustmentPublishesTheEventWithItsDirectionAndWhatTheOperatorWrote() {
        StockMovementRepository stockMovementRepository = mock(StockMovementRepository.class);
        MaterialRepository materialRepository = mock(MaterialRepository.class);
        WarehouseRepository warehouseRepository = mock(WarehouseRepository.class);
        StockMovementMapper stockMovementMapper = mock(StockMovementMapper.class);
        RecordingEventPublisher events = new RecordingEventPublisher();
        Material material = material("MAT-ADJ-EVT");
        Warehouse warehouse = warehouse("WH-ADJ-EVT");
        StockMovementAdjustmentRequest request = new StockMovementAdjustmentRequest(material.getId(), warehouse.getId(),
                StockAdjustmentDirection.NEGATIVE, new BigDecimal("1.500000"), null, "RECUENTO-39", "3 unidades danadas");
        StockMovement movement = movement(StockMovementType.NEGATIVE_ADJUSTMENT, new BigDecimal("1.500000"));
        movement.setExternalReference("RECUENTO-39");
        movement.setNotes("3 unidades danadas");
        setId(movement, UUID.randomUUID());
        when(stockMovementMapper.toAdjustmentEntity(request)).thenReturn(movement);
        when(materialRepository.findById(material.getId())).thenReturn(Optional.of(material));
        when(warehouseRepository.findById(warehouse.getId())).thenReturn(Optional.of(warehouse));
        when(stockMovementRepository.save(movement)).thenReturn(movement);
        StockMovementServiceImpl service = new StockMovementServiceImpl(stockMovementRepository, materialRepository, warehouseRepository,
                mock(SupplierRepository.class), mock(ProjectRepository.class), mock(ReservationRepository.class), stockMovementMapper,
                mock(InventoryBalanceService.class), mock(InventoryValidationService.class), mock(ReservationEngine.class),
                mock(IdempotentRequestService.class), events);

        service.registerAdjustment(request);

        assertEquals(List.of("adjustment.registered"), events.names());
        DomainEvent event = events.published.getFirst();
        assertEquals(movement.getId().toString(), event.entityId());
        assertEquals("NEGATIVE", event.values().get("direction"));
        assertEquals("NEGATIVE_ADJUSTMENT", event.values().get("movementType"));
        assertEquals(new BigDecimal("1.500000"), event.values().get("quantity"));
        assertEquals(new BigDecimal("-1.500000"), event.values().get("signedQuantity"));
        assertEquals("RECUENTO-39", event.values().get("externalReference"));
        assertEquals("3 unidades danadas", event.values().get("notes"));
        assertEquals("MAT-ADJ-EVT", event.values().get("materialCode"));
        assertEquals("WH-ADJ-EVT", event.values().get("warehouseCode"));
        assertNotNull(event.values().get("occurredAt"));
    }

    /** El saldo con el material bloqueado: solo se preparan los bloqueos que el test va a pedir (strict stubs). */
    private static InventoryBalanceServiceImpl balanceService(InventoryBalanceRepository repository, AuditorAware<String> auditorAware,
                                                              Material... locked) {
        MaterialRepository materialRepository = mock(MaterialRepository.class);
        for (Material material : locked) {
            when(materialRepository.findByIdForUpdate(material.getId())).thenReturn(Optional.of(material));
        }
        return new InventoryBalanceServiceImpl(repository, materialRepository, new RecordingEventPublisher(), auditorAware);
    }

    /** Lo que cada gancho cuenta hacia fuera, tal como llegaria al outbox. */
    static final class RecordingEventPublisher implements DomainEventPublisher {
        final List<DomainEvent> published = new ArrayList<>();
        final List<UUID> operationIds = new ArrayList<>();

        @Override
        public void publish(DomainEvent event) {
            publish(UUID.randomUUID(), event);
        }

        @Override
        public void publish(UUID operationId, DomainEvent event) {
            operationIds.add(operationId);
            published.add(event);
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        List<String> names() {
            return published.stream().map(event -> event.entityName() + "." + event.eventName()).toList();
        }
    }

    private static final class RecordingEntityHandler implements MasterDataEntityHandler {

        private final String entityName;
        private final List<String> calls = new ArrayList<>();

        private RecordingEntityHandler(String entityName) {
            this.entityName = entityName;
        }

        @Override
        public String entityName() {
            return entityName;
        }

        @Override
        public void onCreated(MasterDataChangedMessage message, MasterDataEventContext context) {
            calls.add("onCreated");
        }

        @Override
        public void onUpdated(MasterDataChangedMessage message, MasterDataEventContext context) {
            calls.add("onUpdated");
        }

        @Override
        public void onDeleted(MasterDataChangedMessage message, MasterDataEventContext context) {
            calls.add("onDeleted");
        }
    }

    private static MasterDataChangedMessage masterDataMessage(String entityName, MasterDataOperation operation) {
        return new MasterDataChangedMessage(
                UUID.randomUUID(),
                entityName + "-42",
                "mto-configuration",
                Instant.now(),
                "MASTER_DATA_" + entityName.toUpperCase().replace('-', '_') + "_" + operation.name(),
                new MasterDataChangedEvent(entityName, "42", operation, Map.of("name", "Barcelona Sants")),
                "hash");
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

    private static ReservationRequest idempotentReservation(String quantity) {
        return new ReservationRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), new BigDecimal(quantity), null);
    }

    private static IdempotentRequest storedRequest(IdempotentOperation operation, String key, String fingerprint, UUID resourceId) {
        return IdempotentRequest.builder()
                .operation(operation)
                .idempotencyKey(key)
                .requestHash(fingerprint)
                .resourceId(resourceId)
                .build();
    }

    private static ReservationResponse reservationResponse(Reservation reservation) {
        return new ReservationResponse(reservation.getId(), null, null, null, reservation.getQuantity(),
                ReservationStatusDto.ACTIVE, Instant.EPOCH, null, true, null);
    }

    private static StockMovementResponse movementResponse(StockMovement movement) {
        return new StockMovementResponse(movement.getId(), null, null, StockMovementTypeDto.OUTPUT, movement.getQuantity(),
                movement.getQuantity().negate(), Instant.EPOCH, null, null, null, null, "MO-000001", null, null);
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

    private static Project project(String code) {
        Project project = Project.builder()
                .code(code)
                .name("Project " + code)
                .build();
        setId(project, UUID.randomUUID());
        return project;
    }

    private static Supplier supplier(String code) {
        Supplier supplier = Supplier.builder()
                .code(code)
                .name("Supplier " + code)
                .build();
        setId(supplier, UUID.randomUUID());
        return supplier;
    }

    private static Assembly assembly(String code) {
        Assembly assembly = Assembly.builder()
                .code(code)
                .name("Assembly " + code)
                .build();
        setId(assembly, UUID.randomUUID());
        return assembly;
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