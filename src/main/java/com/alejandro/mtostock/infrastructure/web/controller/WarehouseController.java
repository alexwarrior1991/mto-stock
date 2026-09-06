package com.alejandro.mtostock.infrastructure.web.controller;

import com.alejandro.mtostock.application.dto.audit.EntityRevisionResponse;
import com.alejandro.mtostock.application.dto.common.PageResponse;
import com.alejandro.mtostock.application.dto.error.ApiErrorResponse;
import com.alejandro.mtostock.application.dto.material.MaterialStockResponse;
import com.alejandro.mtostock.application.dto.stock.StockMovementResponse;
import com.alejandro.mtostock.application.dto.stock.StockMovementTransferRequest;
import com.alejandro.mtostock.application.dto.warehouse.WarehouseRequest;
import com.alejandro.mtostock.application.dto.warehouse.WarehouseResponse;
import com.alejandro.mtostock.application.dto.warehouse.WarehouseUpdateRequest;
import com.alejandro.mtostock.application.service.WarehouseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * REST controller exposing warehouse catalogue and transfer use cases.
 */
@Validated
@RestController
@RequestMapping("/api/v1/inventory/warehouses")
@Tag(name = "Warehouses", description = "Warehouse catalogue, warehouse stock views and transfers.")
public class WarehouseController {

    private static final Logger LOGGER = LoggerFactory.getLogger(WarehouseController.class);

    private final WarehouseService warehouseService;

    public WarehouseController(WarehouseService warehouseService) {
        this.warehouseService = warehouseService;
    }

    /**
     * Creates a warehouse and returns HTTP 201.
     */
    @Operation(summary = "Create warehouse", description = "Creates an inventory warehouse used by movements and reservations.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Warehouse created"),
            @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Duplicate warehouse code", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PostMapping
    public ResponseEntity<WarehouseResponse> create(@Valid @RequestBody WarehouseRequest request) {
        LOGGER.debug("HTTP request to create warehouse code={}", request.code());
        WarehouseResponse response = warehouseService.create(request);
        return ResponseEntity.created(URI.create("/api/v1/inventory/warehouses/" + response.id())).body(response);
    }

    /**
     * Updates an existing warehouse.
     */
    @Operation(summary = "Update warehouse", description = "Updates an existing warehouse catalogue record.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Warehouse updated"),
            @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Warehouse not found", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PutMapping("/{id}")
    public ResponseEntity<WarehouseResponse> update(@Parameter(description = "Warehouse UUID") @PathVariable UUID id,
                                                    @Valid @RequestBody WarehouseUpdateRequest request) {
        LOGGER.debug("HTTP request to update warehouse id={}", id);
        return ResponseEntity.ok(warehouseService.update(id, request));
    }

    /**
     * Returns one warehouse by UUID.
     */
    @Operation(summary = "Get warehouse", description = "Returns one warehouse by UUID.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Warehouse found"),
            @ApiResponse(responseCode = "404", description = "Warehouse not found", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/{id}")
    public ResponseEntity<WarehouseResponse> findById(@Parameter(description = "Warehouse UUID") @PathVariable UUID id) {
        LOGGER.debug("HTTP request to find warehouse id={}", id);
        return ResponseEntity.ok(warehouseService.findById(id));
    }

    /**
     * Lists warehouses using pageable/sort parameters.
     */
    @Operation(summary = "List warehouses", description = "Returns a pageable list of warehouses.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Warehouse page returned"),
            @ApiResponse(responseCode = "500", description = "Unexpected server error", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping
    public ResponseEntity<PageResponse<WarehouseResponse>> findAll(@PageableDefault(size = 20) Pageable pageable) {
        LOGGER.debug("HTTP request to list warehouses");
        return ResponseEntity.ok(warehouseService.findAll(pageable));
    }

    /**
     * Calculates material stock for one warehouse.
     */
    @Operation(summary = "Get warehouse material stock", description = "Calculates physical, reserved and available stock for one material in one warehouse.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Warehouse material stock returned"),
            @ApiResponse(responseCode = "404", description = "Warehouse or material not found", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/{id}/inventory")
    public ResponseEntity<MaterialStockResponse> inventory(@Parameter(description = "Warehouse UUID") @PathVariable UUID id,
                                                           @Parameter(description = "Material UUID") @RequestParam UUID materialId) {
        LOGGER.debug("HTTP request to calculate warehouse inventory warehouseId={} materialId={}", id, materialId);
        return ResponseEntity.ok(warehouseService.calculateMaterialStock(id, materialId));
    }

    /**
     * Atomically transfers stock between warehouses through the business service.
     */
    @Operation(summary = "Transfer stock", description = "Atomically creates outgoing and incoming stock movements for a warehouse transfer.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Transfer movements created"),
            @ApiResponse(responseCode = "400", description = "Invalid transfer", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Insufficient stock", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "Domain rule violation", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PostMapping("/transfers")
    public ResponseEntity<List<StockMovementResponse>> transfer(@Valid @RequestBody StockMovementTransferRequest request) {
        LOGGER.debug("HTTP request to transfer stock materialId={} sourceWarehouseId={} targetWarehouseId={}",
                request.materialId(), request.sourceWarehouseId(), request.targetWarehouseId());
        return ResponseEntity.created(URI.create("/api/v1/inventory/movements")).body(warehouseService.transfer(request));
    }

    /**
     * Returns the change history recorded by Hibernate Envers, newest revision first.
     */
    @Operation(summary = "Get warehouse change history", description = "Returns the audited change history of one warehouse, newest revision first.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Revision page returned"),
            @ApiResponse(responseCode = "404", description = "Warehouse not found", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/{id}/revisions")
    public ResponseEntity<PageResponse<EntityRevisionResponse<WarehouseResponse>>> revisions(
            @Parameter(description = "Warehouse UUID") @PathVariable UUID id,
            @PageableDefault(size = 20) Pageable pageable) {
        LOGGER.debug("HTTP request to read warehouse revision history id={}", id);
        return ResponseEntity.ok(warehouseService.findRevisions(id, pageable));
    }
}
