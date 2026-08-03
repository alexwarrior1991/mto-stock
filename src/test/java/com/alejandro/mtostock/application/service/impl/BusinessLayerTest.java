package com.alejandro.mtostock.application.service.impl;

import com.alejandro.mtostock.application.dto.assembly.AssemblyAvailabilityResponse;
import com.alejandro.mtostock.application.dto.assembly.AssemblySummaryResponse;
import com.alejandro.mtostock.application.dto.material.MaterialSummaryResponse;
import com.alejandro.mtostock.application.dto.stock.StockMovementTransferRequest;
import com.alejandro.mtostock.application.dto.warehouse.WarehouseSummaryResponse;
import com.alejandro.mtostock.application.mapper.AssemblyMapper;
import com.alejandro.mtostock.application.mapper.MaterialMapper;
import com.alejandro.mtostock.application.mapper.StockMovementMapper;
import com.alejandro.mtostock.application.mapper.WarehouseMapper;
import com.alejandro.mtostock.application.service.InventoryValidationService;
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
import com.alejandro.mtostock.infrastructure.persistence.repository.MaterialRepository;
import com.alejandro.mtostock.infrastructure.persistence.repository.ProjectRepository;
import com.alejandro.mtostock.infrastructure.persistence.repository.ReservationRepository;
import com.alejandro.mtostock.infrastructure.persistence.repository.StockMovementRepository;
import com.alejandro.mtostock.infrastructure.persistence.repository.WarehouseRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BusinessLayerTest {

    @Test
    void stockCalculationUsesMovementsAndReservationsWithoutStoredStock() {
        StockMovementRepository stockMovementRepository = mock(StockMovementRepository.class);
        ReservationRepository reservationRepository = mock(ReservationRepository.class);
        UUID materialId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        when(stockMovementRepository.calculateSignedQuantity(eq(materialId), eq(warehouseId), isNull(), anyCollection(), eq(BigDecimal.ZERO)))
                .thenReturn(new BigDecimal("8.000000"));
        when(reservationRepository.calculateActiveReservedQuantity(materialId, warehouseId, BigDecimal.ZERO))
                .thenReturn(new BigDecimal("3.000000"));
        StockCalculationServiceImpl service = new StockCalculationServiceImpl(
                stockMovementRepository,
                reservationRepository,
                mock(MaterialRepository.class),
                mock(WarehouseRepository.class),
                mock(MaterialMapper.class),
                mock(WarehouseMapper.class)
        );

        assertEquals(new BigDecimal("8.000000"), service.calculatePhysicalStock(materialId, warehouseId));
        assertEquals(new BigDecimal("3.000000"), service.calculateReservedStock(materialId, warehouseId));
        assertEquals(new BigDecimal("5.000000"), service.calculateAvailableStock(materialId, warehouseId));
        verify(stockMovementRepository, times(2)).calculateSignedQuantity(eq(materialId), eq(warehouseId), isNull(), anyCollection(), eq(BigDecimal.ZERO));
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
    void reservationEngineConsumesActiveReservationByReleasingIt() {
        ReservationRepository reservationRepository = mock(ReservationRepository.class);
        InventoryValidationService validationService = mock(InventoryValidationService.class);
        Reservation reservation = reservation();
        when(reservationRepository.findById(reservation.getId())).thenReturn(Optional.of(reservation));
        ReservationEngineImpl service = new ReservationEngineImpl(
                reservationRepository,
                mock(MaterialRepository.class),
                mock(WarehouseRepository.class),
                mock(ProjectRepository.class),
                validationService
        );

        Reservation consumedReservation = service.consume(reservation.getId());

        assertEquals(ReservationStatus.RELEASED, consumedReservation.getStatus());
        assertNotNull(consumedReservation.getReleasedAt());
        verify(validationService).validateReservationCanChange(reservation);
    }

    @Test
    void transferCreatesRelatedMovementsAndIsTransactional() throws NoSuchMethodException {
        MaterialRepository materialRepository = mock(MaterialRepository.class);
        WarehouseRepository warehouseRepository = mock(WarehouseRepository.class);
        StockMovementRepository stockMovementRepository = mock(StockMovementRepository.class);
        StockMovementMapper stockMovementMapper = mock(StockMovementMapper.class);
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
                validationService
        );

        var movements = service.transfer(request);

        assertEquals(2, movements.size());
        assertSame(incomingMovement, outgoingMovement.getRelatedMovement());
        assertSame(outgoingMovement, incomingMovement.getRelatedMovement());
        assertNotNull(TransferServiceImpl.class.getDeclaredMethod("transfer", StockMovementTransferRequest.class).getAnnotation(Transactional.class));
        verify(validationService).validateAvailableStock(material.getId(), sourceWarehouse.getId(), request.quantity());
    }

    @Test
    void businessPhaseDoesNotIntroduceRestControllers() throws IOException {
        Path mainSources = Path.of("src", "main", "java");

        try (var paths = Files.walk(mainSources)) {
            assertTrue(paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .noneMatch(path -> containsRestController(path)));
        }
    }

    private static boolean containsRestController(Path path) {
        try {
            return Files.readString(path).contains("@RestController");
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