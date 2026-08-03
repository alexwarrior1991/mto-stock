package com.alejandro.mtostock.infrastructure.web.controller;

import com.alejandro.mtostock.application.dto.common.PageResponse;
import com.alejandro.mtostock.application.dto.error.ApiErrorResponse;
import com.alejandro.mtostock.application.dto.stock.StockMovementAdjustmentRequest;
import com.alejandro.mtostock.application.dto.stock.StockMovementEntryRequest;
import com.alejandro.mtostock.application.dto.stock.StockMovementOutputRequest;
import com.alejandro.mtostock.application.dto.stock.StockMovementResponse;
import com.alejandro.mtostock.application.dto.stock.StockMovementTransferRequest;
import com.alejandro.mtostock.application.service.StockMovementService;
import com.alejandro.mtostock.application.service.WarehouseService;
import com.alejandro.mtostock.infrastructure.persistence.entity.StockMovementType;
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
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * REST controller exposing append-only stock ledger operations.
 */
@Validated
@RestController
@RequestMapping("/api/v1/inventory/movements")
@Tag(name = "Stock Movements", description = "Stock entries, outputs, adjustments, transfers and movement history.")
public class StockMovementController {

    private static final Logger LOGGER = LoggerFactory.getLogger(StockMovementController.class);

    private final StockMovementService stockMovementService;
    private final WarehouseService warehouseService;

    public StockMovementController(StockMovementService stockMovementService, WarehouseService warehouseService) {
        this.stockMovementService = stockMovementService;
        this.warehouseService = warehouseService;
    }

    /**
     * Registers a positive stock entry.
     */
    @Operation(summary = "Register stock entry", description = "Creates a positive stock entry movement, optionally linked to a supplier.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Stock entry created"),
            @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "Domain rule violation", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PostMapping("/entries")
    public ResponseEntity<StockMovementResponse> entry(@Valid @RequestBody StockMovementEntryRequest request) {
        LOGGER.debug("HTTP request to register stock entry materialId={} warehouseId={}", request.materialId(), request.warehouseId());
        StockMovementResponse response = stockMovementService.registerEntry(request);
        return ResponseEntity.created(URI.create("/api/v1/inventory/movements/" + response.id())).body(response);
    }

    /**
     * Registers a negative stock output.
     */
    @Operation(summary = "Register stock output", description = "Creates a negative stock output movement, optionally linked to a project or reservation.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Stock output created"),
            @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Insufficient stock", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "Domain rule violation", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PostMapping("/outputs")
    public ResponseEntity<StockMovementResponse> output(@Valid @RequestBody StockMovementOutputRequest request) {
        LOGGER.debug("HTTP request to register stock output materialId={} warehouseId={}", request.materialId(), request.warehouseId());
        StockMovementResponse response = stockMovementService.registerOutput(request);
        return ResponseEntity.created(URI.create("/api/v1/inventory/movements/" + response.id())).body(response);
    }

    /**
     * Registers a stock adjustment.
     */
    @Operation(summary = "Register stock adjustment", description = "Creates a positive or negative stock adjustment movement.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Stock adjustment created"),
            @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Insufficient stock for negative adjustment", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "Domain rule violation", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PostMapping("/adjustments")
    public ResponseEntity<StockMovementResponse> adjustment(@Valid @RequestBody StockMovementAdjustmentRequest request) {
        LOGGER.debug("HTTP request to register stock adjustment materialId={} warehouseId={}", request.materialId(), request.warehouseId());
        StockMovementResponse response = stockMovementService.registerAdjustment(request);
        return ResponseEntity.created(URI.create("/api/v1/inventory/movements/" + response.id())).body(response);
    }

    /**
     * Registers an atomic transfer between warehouses.
     */
    @Operation(summary = "Transfer stock", description = "Atomically creates outgoing and incoming transfer movements.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Transfer movements created"),
            @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
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
     * Returns one movement by UUID.
     */
    @Operation(summary = "Get movement", description = "Returns one append-only stock movement by UUID.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Movement found"),
            @ApiResponse(responseCode = "404", description = "Movement not found", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/{id}")
    public ResponseEntity<StockMovementResponse> findById(@Parameter(description = "Stock movement UUID") @PathVariable UUID id) {
        LOGGER.debug("HTTP request to find stock movement id={}", id);
        return ResponseEntity.ok(stockMovementService.findById(id));
    }

    /**
     * Searches movement history using composable filters.
     */
    @Operation(summary = "Search movement history", description = "Searches stock movements by type, warehouse, project, material, date range and user.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Movement page returned"),
            @ApiResponse(responseCode = "400", description = "Invalid filter", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping
    public ResponseEntity<PageResponse<StockMovementResponse>> search(
            @Parameter(description = "Movement type filter", example = "ENTRY") @RequestParam(required = false) StockMovementType movementType,
            @Parameter(description = "Warehouse UUID filter") @RequestParam(required = false) UUID warehouseId,
            @Parameter(description = "Project UUID filter") @RequestParam(required = false) UUID projectId,
            @Parameter(description = "Material UUID filter") @RequestParam(required = false) UUID materialId,
            @Parameter(description = "Inclusive movement date-time lower bound") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant dateFrom,
            @Parameter(description = "Inclusive movement date-time upper bound") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant dateTo,
            @Parameter(description = "Audit user filter", example = "warehouse.operator") @RequestParam(required = false) String user,
            @PageableDefault(size = 20) Pageable pageable) {
        LOGGER.debug("HTTP request to search stock movements");
        return ResponseEntity.ok(stockMovementService.search(movementType, warehouseId, projectId, materialId, dateFrom, dateTo, user, pageable));
    }
}