package com.alejandro.mtostock.infrastructure.web.controller;

import com.alejandro.mtostock.application.dto.common.PageResponse;
import com.alejandro.mtostock.application.dto.error.ApiErrorResponse;
import com.alejandro.mtostock.application.dto.material.MaterialRequest;
import com.alejandro.mtostock.application.dto.material.MaterialResponse;
import com.alejandro.mtostock.application.dto.material.MaterialStockResponse;
import com.alejandro.mtostock.application.dto.material.MaterialUpdateRequest;
import com.alejandro.mtostock.application.dto.stock.StockMovementResponse;
import com.alejandro.mtostock.application.service.MaterialService;
import com.alejandro.mtostock.application.service.StockMovementService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

/**
 * REST controller exposing material catalogue, stock and movement-history use cases.
 */
@Validated
@RestController
@RequestMapping("/api/v1/inventory/materials")
@Tag(name = "Materials", description = "Material catalogue, calculated stock and stock-ledger visibility.")
public class MaterialController {

    private static final Logger LOGGER = LoggerFactory.getLogger(MaterialController.class);

    private final MaterialService materialService;
    private final StockMovementService stockMovementService;

    public MaterialController(MaterialService materialService, StockMovementService stockMovementService) {
        this.materialService = materialService;
        this.stockMovementService = stockMovementService;
    }

    /**
     * Creates a new material and returns HTTP 201 with the created representation.
     */
    @Operation(summary = "Create material", description = "Creates a catalogue material. Material codes must be unique.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Material created"),
            @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Duplicate material code", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PostMapping
    public ResponseEntity<MaterialResponse> create(@Valid @RequestBody MaterialRequest request) {
        LOGGER.debug("HTTP request to create material code={}", request.code());
        MaterialResponse response = materialService.create(request);
        return ResponseEntity.created(URI.create("/api/v1/inventory/materials/" + response.id())).body(response);
    }

    /**
     * Updates an existing material using the application service.
     */
    @Operation(summary = "Update material", description = "Updates an existing material without exposing persistence entities.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Material updated"),
            @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Material not found", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Duplicate material code", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PutMapping("/{id}")
    public ResponseEntity<MaterialResponse> update(
            @Parameter(description = "Material UUID", example = "018f60be-1b9a-7cc3-8c6b-2f93e8c6a020") @PathVariable UUID id,
            @Valid @RequestBody MaterialUpdateRequest request) {
        LOGGER.debug("HTTP request to update material id={}", id);
        return ResponseEntity.ok(materialService.update(id, request));
    }

    /**
     * Returns a material by identifier.
     */
    @Operation(summary = "Get material", description = "Returns one material by UUID.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Material found"),
            @ApiResponse(responseCode = "404", description = "Material not found", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/{id}")
    public ResponseEntity<MaterialResponse> findById(
            @Parameter(description = "Material UUID", example = "018f60be-1b9a-7cc3-8c6b-2f93e8c6a020") @PathVariable UUID id) {
        LOGGER.debug("HTTP request to find material id={}", id);
        return ResponseEntity.ok(materialService.findById(id));
    }

    /**
     * Searches materials using service-level specification filters and pagination.
     */
    @Operation(summary = "Search materials", description = "Searches materials by code, name, active state, warehouse and minimum-stock condition.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Material page returned"),
            @ApiResponse(responseCode = "400", description = "Invalid filter", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping
    public ResponseEntity<PageResponse<MaterialResponse>> search(
            @Parameter(description = "Material code filter", example = "MAT-COPPER-50") @RequestParam(required = false) String code,
            @Parameter(description = "Material name filter", example = "Copper") @RequestParam(required = false) String name,
            @Parameter(description = "Active state filter", example = "true") @RequestParam(required = false) Boolean active,
            @Parameter(description = "Warehouse UUID used by stock filters") @RequestParam(required = false) UUID warehouseId,
            @Parameter(description = "Return materials below minimum calculated stock", example = "false") @RequestParam(required = false) Boolean belowMinimum,
            @PageableDefault(size = 20) Pageable pageable) {
        LOGGER.debug("HTTP request to search materials");
        return ResponseEntity.ok(materialService.search(code, name, active, warehouseId, belowMinimum, pageable));
    }

    /**
     * Returns materials whose calculated available stock is below their configured minimum level.
     */
    @Operation(summary = "Search low-stock materials", description = "Returns active materials below their configured minimum stock level.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Low-stock material page returned"),
            @ApiResponse(responseCode = "400", description = "Invalid filter", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/low-stock")
    public ResponseEntity<PageResponse<MaterialResponse>> lowStock(
            @Parameter(description = "Optional warehouse UUID. Omit for global stock.") @RequestParam(required = false) UUID warehouseId,
            @PageableDefault(size = 20) Pageable pageable) {
        LOGGER.debug("HTTP request to search low-stock materials warehouseId={}", warehouseId);
        return ResponseEntity.ok(materialService.search(null, null, true, warehouseId, true, pageable));
    }

    /**
     * Calculates material stock from movements and active reservations.
     */
    @Operation(summary = "Get material stock", description = "Calculates physical, reserved and available stock without reading any stored stock field.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Material stock returned"),
            @ApiResponse(responseCode = "404", description = "Material or warehouse not found", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/{id}/stock")
    public ResponseEntity<MaterialStockResponse> stock(
            @Parameter(description = "Material UUID") @PathVariable UUID id,
            @Parameter(description = "Optional warehouse UUID. Omit for global stock.") @RequestParam(required = false) UUID warehouseId) {
        LOGGER.debug("HTTP request to calculate material stock id={} warehouseId={}", id, warehouseId);
        return ResponseEntity.ok(materialService.calculateStock(id, warehouseId));
    }

    /**
     * Returns movement history for one material by delegating to the stock movement service.
     */
    @Operation(summary = "Get material movement history", description = "Returns stock-ledger movements for a material with optional warehouse, date and user filters.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Movement page returned"),
            @ApiResponse(responseCode = "400", description = "Invalid filter", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/{id}/movements")
    public ResponseEntity<PageResponse<StockMovementResponse>> movements(
            @Parameter(description = "Material UUID") @PathVariable UUID id,
            @Parameter(description = "Optional warehouse UUID") @RequestParam(required = false) UUID warehouseId,
            @Parameter(description = "Inclusive movement date-time lower bound") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant dateFrom,
            @Parameter(description = "Inclusive movement date-time upper bound") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant dateTo,
            @Parameter(description = "Audit user filter", example = "warehouse.operator") @RequestParam(required = false) String user,
            @PageableDefault(size = 20) Pageable pageable) {
        LOGGER.debug("HTTP request to search material movements id={}", id);
        return ResponseEntity.ok(stockMovementService.search(null, warehouseId, null, id, dateFrom, dateTo, user, pageable));
    }
}