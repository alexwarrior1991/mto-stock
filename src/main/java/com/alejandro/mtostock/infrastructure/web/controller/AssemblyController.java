package com.alejandro.mtostock.infrastructure.web.controller;

import com.alejandro.mtostock.application.dto.assembly.AssemblyAvailabilityResponse;
import com.alejandro.mtostock.application.dto.assembly.AssemblyRequest;
import com.alejandro.mtostock.application.dto.assembly.AssemblyResponse;
import com.alejandro.mtostock.application.dto.assembly.AssemblyUpdateRequest;
import com.alejandro.mtostock.application.dto.common.PageResponse;
import com.alejandro.mtostock.application.dto.error.ApiErrorResponse;
import com.alejandro.mtostock.application.service.AssemblyService;
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
import java.util.UUID;

/**
 * REST controller exposing virtual assembly and BOM availability use cases.
 */
@Validated
@RestController
@RequestMapping("/api/v1/inventory/assemblies")
@Tag(name = "Assemblies", description = "Virtual assemblies, bill of materials and availability calculations.")
public class AssemblyController {

    private static final Logger LOGGER = LoggerFactory.getLogger(AssemblyController.class);

    private final AssemblyService assemblyService;

    public AssemblyController(AssemblyService assemblyService) {
        this.assemblyService = assemblyService;
    }

    /**
     * Creates an assembly with its BOM components.
     */
    @Operation(summary = "Create assembly", description = "Creates a virtual assembly with its bill of materials.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Assembly created"),
            @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Duplicate assembly code", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "Invalid BOM", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PostMapping
    public ResponseEntity<AssemblyResponse> create(@Valid @RequestBody AssemblyRequest request) {
        LOGGER.debug("HTTP request to create assembly code={}", request.code());
        AssemblyResponse response = assemblyService.create(request);
        return ResponseEntity.created(URI.create("/api/v1/inventory/assemblies/" + response.id())).body(response);
    }

    /**
     * Updates an assembly and its BOM definition.
     */
    @Operation(summary = "Update assembly", description = "Updates an existing assembly and delegates BOM rules to the service layer.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Assembly updated"),
            @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Assembly not found", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Duplicate assembly code", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "Invalid BOM", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PutMapping("/{id}")
    public ResponseEntity<AssemblyResponse> update(@Parameter(description = "Assembly UUID") @PathVariable UUID id,
                                                   @Valid @RequestBody AssemblyUpdateRequest request) {
        LOGGER.debug("HTTP request to update assembly id={}", id);
        return ResponseEntity.ok(assemblyService.update(id, request));
    }

    /**
     * Returns one assembly including its BOM response.
     */
    @Operation(summary = "Get assembly", description = "Returns one assembly and its bill of materials by UUID.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Assembly found"),
            @ApiResponse(responseCode = "404", description = "Assembly not found", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/{id}")
    public ResponseEntity<AssemblyResponse> findById(@Parameter(description = "Assembly UUID") @PathVariable UUID id) {
        LOGGER.debug("HTTP request to find assembly id={}", id);
        return ResponseEntity.ok(assemblyService.findById(id));
    }

    /**
     * Searches assemblies using service-level filters and pagination.
     */
    @Operation(summary = "Search assemblies", description = "Searches assemblies by code, name and active state.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Assembly page returned"),
            @ApiResponse(responseCode = "400", description = "Invalid filter", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping
    public ResponseEntity<PageResponse<AssemblyResponse>> search(
            @Parameter(description = "Assembly code filter", example = "ASM-CAT-001") @RequestParam(required = false) String code,
            @Parameter(description = "Assembly name filter", example = "Bracket") @RequestParam(required = false) String name,
            @Parameter(description = "Active state filter", example = "true") @RequestParam(required = false) Boolean active,
            @PageableDefault(size = 20) Pageable pageable) {
        LOGGER.debug("HTTP request to search assemblies");
        return ResponseEntity.ok(assemblyService.search(code, name, active, pageable));
    }

    /**
     * Calculates assembly availability from component stock.
     */
    @Operation(summary = "Calculate assembly availability", description = "Calculates maximum producible quantity, limiting component and missing component quantities.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Assembly availability returned"),
            @ApiResponse(responseCode = "404", description = "Assembly or warehouse not found", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "Invalid BOM", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/{id}/availability")
    public ResponseEntity<AssemblyAvailabilityResponse> availability(@Parameter(description = "Assembly UUID") @PathVariable UUID id,
                                                                     @Parameter(description = "Warehouse UUID used for component availability") @RequestParam UUID warehouseId) {
        LOGGER.debug("HTTP request to calculate assembly availability id={} warehouseId={}", id, warehouseId);
        return ResponseEntity.ok(assemblyService.calculateAvailability(id, warehouseId));
    }

    /**
     * Alias for clients that use the ERP production-capacity terminology.
     */
    @Operation(summary = "Calculate production capacity", description = "Returns the same BOM-based availability model using production-capacity terminology.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Production capacity returned"),
            @ApiResponse(responseCode = "404", description = "Assembly or warehouse not found", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "Invalid BOM", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/{id}/production-capacity")
    public ResponseEntity<AssemblyAvailabilityResponse> productionCapacity(@Parameter(description = "Assembly UUID") @PathVariable UUID id,
                                                                           @Parameter(description = "Warehouse UUID used for component availability") @RequestParam UUID warehouseId) {
        LOGGER.debug("HTTP request to calculate assembly production capacity id={} warehouseId={}", id, warehouseId);
        return ResponseEntity.ok(assemblyService.calculateAvailability(id, warehouseId));
    }
}