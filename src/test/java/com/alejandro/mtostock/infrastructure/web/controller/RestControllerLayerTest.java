package com.alejandro.mtostock.infrastructure.web.controller;

import com.alejandro.mtostock.application.dto.audit.EntityRevisionResponse;
import com.alejandro.mtostock.application.dto.audit.RevisionMetadataResponse;
import com.alejandro.mtostock.application.dto.audit.RevisionOperation;
import com.alejandro.mtostock.application.dto.assembly.AssemblyAvailabilityResponse;
import com.alejandro.mtostock.application.dto.common.PageMetadataResponse;
import com.alejandro.mtostock.application.dto.common.PageResponse;
import com.alejandro.mtostock.application.dto.material.MaterialRequest;
import com.alejandro.mtostock.application.dto.material.MaterialResponse;
import com.alejandro.mtostock.application.dto.material.MaterialStockResponse;
import com.alejandro.mtostock.application.dto.project.ProjectRequest;
import com.alejandro.mtostock.application.dto.project.ProjectResponse;
import com.alejandro.mtostock.application.dto.reservation.ReservationRequest;
import com.alejandro.mtostock.application.dto.reservation.ReservationResponse;
import com.alejandro.mtostock.application.dto.reservation.ReservationStatusDto;
import com.alejandro.mtostock.application.dto.stock.StockAdjustmentDirection;
import com.alejandro.mtostock.application.dto.stock.StockMovementAdjustmentRequest;
import com.alejandro.mtostock.application.dto.stock.StockMovementEntryRequest;
import com.alejandro.mtostock.application.dto.stock.StockMovementResponse;
import com.alejandro.mtostock.application.dto.stock.StockMovementTransferRequest;
import com.alejandro.mtostock.application.dto.supplier.SupplierRequest;
import com.alejandro.mtostock.application.dto.supplier.SupplierResponse;
import com.alejandro.mtostock.application.dto.warehouse.WarehouseRequest;
import com.alejandro.mtostock.application.dto.warehouse.WarehouseResponse;
import com.alejandro.mtostock.application.service.AssemblyService;
import com.alejandro.mtostock.application.service.MaterialService;
import com.alejandro.mtostock.application.service.ProjectService;
import com.alejandro.mtostock.application.service.ReservationService;
import com.alejandro.mtostock.application.service.StockMovementService;
import com.alejandro.mtostock.application.service.SupplierService;
import com.alejandro.mtostock.application.service.WarehouseService;
import com.alejandro.mtostock.infrastructure.persistence.entity.ReservationStatus;
import com.alejandro.mtostock.infrastructure.persistence.entity.StockMovementType;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RestControllerLayerTest {

    @Test
    void controllersExposeVersionedResourceBasePathsAndOpenApiTags() {
        assertController(MaterialController.class, "/api/v1/inventory/materials", "Materials");
        assertController(WarehouseController.class, "/api/v1/inventory/warehouses", "Warehouses");
        assertController(AssemblyController.class, "/api/v1/inventory/assemblies", "Assemblies");
        assertController(StockMovementController.class, "/api/v1/inventory/movements", "Stock Movements");
        assertController(ReservationController.class, "/api/v1/inventory/reservations", "Reservations");
        assertController(SupplierController.class, "/api/v1/inventory/suppliers", "Suppliers");
        assertController(ProjectController.class, "/api/v1/inventory/projects", "Projects");
    }

    @Test
    void materialControllerDelegatesCatalogueStockAndMovementUseCases() {
        MaterialService materialService = mock(MaterialService.class);
        StockMovementService stockMovementService = mock(StockMovementService.class);
        MaterialController controller = new MaterialController(materialService, stockMovementService);
        UUID materialId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 20);
        MaterialRequest request = new MaterialRequest("MAT-001", "Copper wire", "M", BigDecimal.TEN);
        MaterialResponse material = materialResponse(materialId);
        PageResponse<MaterialResponse> materialPage = page(material);
        PageResponse<StockMovementResponse> movementPage = page();
        MaterialStockResponse stock = materialStockResponse();

        when(materialService.create(request)).thenReturn(material);
        when(materialService.search("MAT", "Copper", true, warehouseId, false, pageable)).thenReturn(materialPage);
        when(materialService.calculateStock(materialId, warehouseId)).thenReturn(stock);
        when(stockMovementService.search(null, warehouseId, null, materialId, Instant.EPOCH, Instant.EPOCH.plusSeconds(60), "operator", pageable)).thenReturn(movementPage);

        var createResponse = controller.create(request);
        var searchResponse = controller.search("MAT", "Copper", true, warehouseId, false, pageable);
        var stockResponse = controller.stock(materialId, warehouseId);
        var movementsResponse = controller.movements(materialId, warehouseId, Instant.EPOCH, Instant.EPOCH.plusSeconds(60), "operator", pageable);

        assertEquals(HttpStatus.CREATED, createResponse.getStatusCode());
        assertEquals("/api/v1/inventory/materials/" + materialId, createResponse.getHeaders().getLocation().toString());
        assertSame(materialPage, searchResponse.getBody());
        assertSame(stock, stockResponse.getBody());
        assertSame(movementPage, movementsResponse.getBody());
        verify(materialService).create(request);
        verify(materialService).search("MAT", "Copper", true, warehouseId, false, pageable);
        verify(materialService).calculateStock(materialId, warehouseId);
        verify(stockMovementService).search(null, warehouseId, null, materialId, Instant.EPOCH, Instant.EPOCH.plusSeconds(60), "operator", pageable);
    }

    @Test
    void warehouseAndStockMovementControllersDelegateTransferAndLedgerUseCases() {
        WarehouseService warehouseService = mock(WarehouseService.class);
        StockMovementService stockMovementService = mock(StockMovementService.class);
        WarehouseController warehouseController = new WarehouseController(warehouseService);
        StockMovementController stockMovementController = new StockMovementController(stockMovementService, warehouseService);
        UUID materialId = UUID.randomUUID();
        UUID sourceWarehouseId = UUID.randomUUID();
        UUID targetWarehouseId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 20);
        StockMovementTransferRequest transfer = new StockMovementTransferRequest(materialId, sourceWarehouseId, targetWarehouseId,
                BigDecimal.ONE, Instant.EPOCH, "TR-001", "transfer");
        StockMovementEntryRequest entry = new StockMovementEntryRequest(materialId, sourceWarehouseId, null,
                BigDecimal.TEN, Instant.EPOCH, "ENTRY-001", "entry");
        StockMovementAdjustmentRequest adjustment = new StockMovementAdjustmentRequest(materialId, sourceWarehouseId,
                StockAdjustmentDirection.POSITIVE, BigDecimal.ONE, Instant.EPOCH, "ADJ-001", "adjustment");
        StockMovementResponse movement = stockMovementResponse(movementId);
        PageResponse<StockMovementResponse> movements = page(movement);

        when(warehouseService.transfer(transfer)).thenReturn(List.of(movement));
        when(stockMovementService.registerEntry(entry)).thenReturn(movement);
        when(stockMovementService.registerAdjustment(adjustment)).thenReturn(movement);
        when(stockMovementService.findById(movementId)).thenReturn(movement);
        when(stockMovementService.search(StockMovementType.ENTRY, sourceWarehouseId, null, materialId, Instant.EPOCH, Instant.EPOCH.plusSeconds(60), "operator", pageable)).thenReturn(movements);

        var warehouseTransferResponse = warehouseController.transfer(transfer);
        var movementTransferResponse = stockMovementController.transfer(transfer);
        var entryResponse = stockMovementController.entry(entry);
        var adjustmentResponse = stockMovementController.adjustment(adjustment);
        var findResponse = stockMovementController.findById(movementId);
        var searchResponse = stockMovementController.search(StockMovementType.ENTRY, sourceWarehouseId, null, materialId,
                Instant.EPOCH, Instant.EPOCH.plusSeconds(60), "operator", pageable);

        assertEquals(HttpStatus.CREATED, warehouseTransferResponse.getStatusCode());
        assertEquals(HttpStatus.CREATED, movementTransferResponse.getStatusCode());
        assertEquals(HttpStatus.CREATED, entryResponse.getStatusCode());
        assertEquals(HttpStatus.CREATED, adjustmentResponse.getStatusCode());
        assertSame(movement, findResponse.getBody());
        assertSame(movements, searchResponse.getBody());
        verify(warehouseService, times(2)).transfer(transfer);
        verify(stockMovementService).registerEntry(entry);
        verify(stockMovementService).registerAdjustment(adjustment);
        verify(stockMovementService).findById(movementId);
        verify(stockMovementService).search(StockMovementType.ENTRY, sourceWarehouseId, null, materialId, Instant.EPOCH, Instant.EPOCH.plusSeconds(60), "operator", pageable);
    }

    @Test
    void assemblyAndReservationControllersDelegateBusinessOperations() {
        AssemblyService assemblyService = mock(AssemblyService.class);
        ReservationService reservationService = mock(ReservationService.class);
        AssemblyController assemblyController = new AssemblyController(assemblyService);
        ReservationController reservationController = new ReservationController(reservationService);
        UUID assemblyId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 20);
        AssemblyAvailabilityResponse availability = new AssemblyAvailabilityResponse(null, null, BigDecimal.TEN, List.of(), Instant.EPOCH);
        ReservationRequest request = new ReservationRequest(materialId, warehouseId, projectId, BigDecimal.ONE, Instant.EPOCH.plusSeconds(3600));
        ReservationResponse reservation = reservationResponse(reservationId);
        PageResponse<ReservationResponse> reservations = page(reservation);

        when(assemblyService.calculateAvailability(assemblyId, warehouseId)).thenReturn(availability);
        when(reservationService.create(request)).thenReturn(reservation);
        when(reservationService.release(reservationId)).thenReturn(reservation);
        when(reservationService.consume(reservationId)).thenReturn(reservation);
        when(reservationService.search(warehouseId, ReservationStatus.ACTIVE, projectId, materialId, pageable)).thenReturn(reservations);

        var availabilityResponse = assemblyController.availability(assemblyId, warehouseId);
        var productionCapacityResponse = assemblyController.productionCapacity(assemblyId, warehouseId);
        var createResponse = reservationController.create(request);
        var releaseResponse = reservationController.release(reservationId);
        var consumeResponse = reservationController.consume(reservationId);
        var searchResponse = reservationController.search(warehouseId, ReservationStatus.ACTIVE, projectId, materialId, pageable);

        assertSame(availability, availabilityResponse.getBody());
        assertSame(availability, productionCapacityResponse.getBody());
        assertEquals(HttpStatus.CREATED, createResponse.getStatusCode());
        assertSame(reservation, releaseResponse.getBody());
        assertSame(reservation, consumeResponse.getBody());
        assertSame(reservations, searchResponse.getBody());
        verify(assemblyService, times(2)).calculateAvailability(assemblyId, warehouseId);
        verify(reservationService).create(request);
        verify(reservationService).release(reservationId);
        verify(reservationService).consume(reservationId);
        verify(reservationService).search(warehouseId, ReservationStatus.ACTIVE, projectId, materialId, pageable);
    }

    @Test
    void catalogueControllersDelegateCreateAndListUseCases() {
        WarehouseService warehouseService = mock(WarehouseService.class);
        SupplierService supplierService = mock(SupplierService.class);
        ProjectService projectService = mock(ProjectService.class);
        WarehouseController warehouseController = new WarehouseController(warehouseService);
        SupplierController supplierController = new SupplierController(supplierService);
        ProjectController projectController = new ProjectController(projectService);
        Pageable pageable = PageRequest.of(0, 20);
        WarehouseRequest warehouseRequest = new WarehouseRequest("WH-001", "Main warehouse");
        SupplierRequest supplierRequest = new SupplierRequest("SUP-001", "Rail Supplier");
        ProjectRequest projectRequest = new ProjectRequest("PRJ-001", "Catenary renewal");
        WarehouseResponse warehouse = warehouseResponse(UUID.randomUUID());
        SupplierResponse supplier = supplierResponse(UUID.randomUUID());
        ProjectResponse project = projectResponse(UUID.randomUUID());
        PageResponse<WarehouseResponse> warehouses = page(warehouse);
        PageResponse<SupplierResponse> suppliers = page(supplier);
        PageResponse<ProjectResponse> projects = page(project);

        when(warehouseService.create(warehouseRequest)).thenReturn(warehouse);
        when(warehouseService.findAll(pageable)).thenReturn(warehouses);
        when(supplierService.create(supplierRequest)).thenReturn(supplier);
        when(supplierService.findAll(pageable)).thenReturn(suppliers);
        when(projectService.create(projectRequest)).thenReturn(project);
        when(projectService.findAll(pageable)).thenReturn(projects);

        assertEquals(HttpStatus.CREATED, warehouseController.create(warehouseRequest).getStatusCode());
        assertSame(warehouses, warehouseController.findAll(pageable).getBody());
        assertEquals(HttpStatus.CREATED, supplierController.create(supplierRequest).getStatusCode());
        assertSame(suppliers, supplierController.findAll(pageable).getBody());
        assertEquals(HttpStatus.CREATED, projectController.create(projectRequest).getStatusCode());
        assertSame(projects, projectController.findAll(pageable).getBody());
        verify(warehouseService).create(warehouseRequest);
        verify(warehouseService).findAll(pageable);
        verify(supplierService).create(supplierRequest);
        verify(supplierService).findAll(pageable);
        verify(projectService).create(projectRequest);
        verify(projectService).findAll(pageable);
    }

    private static void assertController(Class<?> controllerType, String basePath, String tagName) {
        assertNotNull(controllerType.getAnnotation(RestController.class));
        RequestMapping mapping = controllerType.getAnnotation(RequestMapping.class);
        assertNotNull(mapping);
        assertEquals(1, mapping.value().length);
        assertEquals(basePath, mapping.value()[0]);
        Tag tag = controllerType.getAnnotation(Tag.class);
        assertNotNull(tag);
        assertEquals(tagName, tag.name());
        assertTrue(controllerType.getDeclaredFields().length > 0);
    }

    private static MaterialResponse materialResponse(UUID id) {
        return new MaterialResponse(id, "MAT-001", "Copper wire", "M", BigDecimal.TEN, true, null);
    }

    private static WarehouseResponse warehouseResponse(UUID id) {
        return new WarehouseResponse(id, "WH-001", "Main warehouse", true, null);
    }

    private static SupplierResponse supplierResponse(UUID id) {
        return new SupplierResponse(id, "SUP-001", "Rail Supplier", true, null);
    }

    private static ProjectResponse projectResponse(UUID id) {
        return new ProjectResponse(id, "PRJ-001", "Catenary renewal", true, null);
    }

    private static StockMovementResponse stockMovementResponse(UUID id) {
        return new StockMovementResponse(id, null, null, null, BigDecimal.ONE, BigDecimal.ONE, Instant.EPOCH,
                null, null, null, null, "REF-001", "movement", null);
    }

    private static ReservationResponse reservationResponse(UUID id) {
        return new ReservationResponse(id, null, null, null, BigDecimal.ONE, ReservationStatusDto.ACTIVE,
                Instant.EPOCH, null, true, null);
    }

    private static MaterialStockResponse materialStockResponse() {
        return new MaterialStockResponse(null, null, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, BigDecimal.ONE, false, Instant.EPOCH);
    }

    @SafeVarargs
    private static <T> PageResponse<T> page(T... items) {
        return new PageResponse<>(List.of(items), new PageMetadataResponse(0, items.length == 0 ? 20 : items.length,
                items.length, 1, true, true));
    }

    /**
     * El historial se expone en {@code /revisions} y no en {@code /history} a proposito:
     * {@code /materials/{id}/movements} ya esta documentado como «movement history» y son dos cosas
     * distintas —el libro mayor de existencias frente a quien cambio el registro—, asi que el nombre
     * se fija aqui para que nadie lo unifique por parecer mas natural.
     */
    @Test
    void everyAuditedResourceExposesItsChangeHistoryUnderRevisions() throws NoSuchMethodException {
        assertRevisionsEndpoint(MaterialController.class);
        assertRevisionsEndpoint(SupplierController.class);
        assertRevisionsEndpoint(WarehouseController.class);
        assertRevisionsEndpoint(ProjectController.class);
        assertRevisionsEndpoint(AssemblyController.class);
        assertRevisionsEndpoint(ReservationController.class);
    }

    @Test
    void materialControllerDelegatesTheChangeHistoryToTheService() {
        MaterialService materialService = mock(MaterialService.class);
        MaterialController controller = new MaterialController(materialService, mock(StockMovementService.class));
        UUID materialId = UUID.randomUUID();
        Pageable pageable = PageRequest.of(0, 20);
        PageResponse<EntityRevisionResponse<MaterialResponse>> history = new PageResponse<>(
                List.of(new EntityRevisionResponse<>(
                        new RevisionMetadataResponse(7L, Instant.EPOCH, RevisionOperation.UPDATED,
                                "alejandro", "HTTP", "corr-1"),
                        materialResponse(materialId))),
                new PageMetadataResponse(0, 20, 1, 1, true, true));

        when(materialService.findRevisions(materialId, pageable)).thenReturn(history);

        var response = controller.revisions(materialId, pageable);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(history, response.getBody());
        verify(materialService).findRevisions(materialId, pageable);
    }

    private static void assertRevisionsEndpoint(Class<?> controller) throws NoSuchMethodException {
        var method = controller.getDeclaredMethod("revisions", UUID.class, Pageable.class);
        var mapping = method.getAnnotation(org.springframework.web.bind.annotation.GetMapping.class);
        assertNotNull(mapping, controller.getSimpleName() + " should expose revisions as a GET");
        assertEquals("/{id}/revisions", mapping.value()[0]);
    }
}
