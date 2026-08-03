package com.alejandro.mtostock.infrastructure.web.controller;

import com.alejandro.mtostock.application.dto.common.PageResponse;
import com.alejandro.mtostock.application.dto.error.ApiErrorResponse;
import com.alejandro.mtostock.application.dto.supplier.SupplierRequest;
import com.alejandro.mtostock.application.dto.supplier.SupplierResponse;
import com.alejandro.mtostock.application.dto.supplier.SupplierUpdateRequest;
import com.alejandro.mtostock.application.service.SupplierService;
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
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

/**
 * REST controller exposing supplier catalogue use cases.
 */
@Validated
@RestController
@RequestMapping("/api/v1/inventory/suppliers")
@Tag(name = "Suppliers", description = "Supplier catalogue used by stock entry operations.")
public class SupplierController {

    private static final Logger LOGGER = LoggerFactory.getLogger(SupplierController.class);

    private final SupplierService supplierService;
    public SupplierController(SupplierService supplierService) {
        this.supplierService = supplierService;
    }

    /**
     * Creates a supplier and returns HTTP 201.
     */
    @Operation(summary = "Create supplier", description = "Creates a supplier catalogue record.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Supplier created"),
            @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Duplicate supplier code", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PostMapping
    public ResponseEntity<SupplierResponse> create(@Valid @RequestBody SupplierRequest request) {
        LOGGER.debug("HTTP request to create supplier code={}", request.code());
        SupplierResponse response = supplierService.create(request);
        return ResponseEntity.created(URI.create("/api/v1/inventory/suppliers/" + response.id())).body(response);
    }

    /**
     * Updates a supplier.
     */
    @Operation(summary = "Update supplier", description = "Updates an existing supplier catalogue record.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Supplier updated"),
            @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Supplier not found", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PutMapping("/{id}")
    public ResponseEntity<SupplierResponse> update(@Parameter(description = "Supplier UUID") @PathVariable UUID id,
                                                   @Valid @RequestBody SupplierUpdateRequest request) {
        LOGGER.debug("HTTP request to update supplier id={}", id);
        return ResponseEntity.ok(supplierService.update(id, request));
    }

    /**
     * Returns one supplier by UUID.
     */
    @Operation(summary = "Get supplier", description = "Returns one supplier by UUID.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Supplier found"),
            @ApiResponse(responseCode = "404", description = "Supplier not found", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/{id}")
    public ResponseEntity<SupplierResponse> findById(@Parameter(description = "Supplier UUID") @PathVariable UUID id) {
        LOGGER.debug("HTTP request to find supplier id={}", id);
        return ResponseEntity.ok(supplierService.findById(id));
    }

    /**
     * Lists suppliers with pageable/sort parameters.
     */
    @Operation(summary = "List suppliers", description = "Returns a pageable supplier list.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Supplier page returned"),
            @ApiResponse(responseCode = "500", description = "Unexpected server error", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping
    public ResponseEntity<PageResponse<SupplierResponse>> findAll(@PageableDefault(size = 20) Pageable pageable) {
        LOGGER.debug("HTTP request to list suppliers");
        return ResponseEntity.ok(supplierService.findAll(pageable));
    }
}