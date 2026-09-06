package com.alejandro.mtostock.infrastructure.web.controller;

import com.alejandro.mtostock.application.dto.audit.EntityRevisionResponse;
import com.alejandro.mtostock.application.dto.common.PageResponse;
import com.alejandro.mtostock.application.dto.error.ApiErrorResponse;
import com.alejandro.mtostock.application.dto.reservation.ReservationRequest;
import com.alejandro.mtostock.application.dto.reservation.ReservationResponse;
import com.alejandro.mtostock.application.dto.reservation.ReservationUpdateRequest;
import com.alejandro.mtostock.application.service.ReservationService;
import com.alejandro.mtostock.infrastructure.persistence.entity.ReservationStatus;
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
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * REST controller exposing reservation lifecycle use cases.
 */
@Validated
@RestController
@RequestMapping("/api/v1/inventory/reservations")
@Tag(name = "Reservations", description = "Reservations that reduce available stock without changing physical stock.")
public class ReservationController {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReservationController.class);

    private final ReservationService reservationService;
    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    /**
     * Creates a reservation after service-level stock validation.
     */
    @Operation(summary = "Create reservation", description = "Reserves available stock without creating stock movements.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Reservation created"),
            @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Insufficient available stock", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "Reservation rule violation", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PostMapping
    public ResponseEntity<ReservationResponse> create(@Valid @RequestBody ReservationRequest request) {
        LOGGER.debug("HTTP request to create reservation materialId={} warehouseId={}", request.materialId(), request.warehouseId());
        ReservationResponse response = reservationService.create(request);
        return ResponseEntity.created(URI.create("/api/v1/inventory/reservations/" + response.id())).body(response);
    }

    /**
     * Updates an existing reservation.
     */
    @Operation(summary = "Update reservation", description = "Updates an active reservation when lifecycle rules allow it.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Reservation updated"),
            @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Reservation not found", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Insufficient available stock", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "Reservation rule violation", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PutMapping("/{id}")
    public ResponseEntity<ReservationResponse> update(@Parameter(description = "Reservation UUID") @PathVariable UUID id,
                                                      @Valid @RequestBody ReservationUpdateRequest request) {
        LOGGER.debug("HTTP request to update reservation id={}", id);
        return ResponseEntity.ok(reservationService.update(id, request));
    }

    /**
     * Cancels a reservation and releases its available-stock hold.
     */
    @Operation(summary = "Cancel reservation", description = "Cancels a reservation. Reservations do not modify physical stock.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Reservation cancelled"),
            @ApiResponse(responseCode = "404", description = "Reservation not found", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "Reservation rule violation", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<ReservationResponse> cancel(@Parameter(description = "Reservation UUID") @PathVariable UUID id) {
        LOGGER.debug("HTTP request to cancel reservation id={}", id);
        return ResponseEntity.ok(reservationService.cancel(id));
    }

    /**
     * Releases a reservation without consuming physical stock.
     */
    @Operation(summary = "Release reservation", description = "Releases reserved quantity while leaving stock movements unchanged.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Reservation released"),
            @ApiResponse(responseCode = "404", description = "Reservation not found", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "Reservation rule violation", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PostMapping("/{id}/release")
    public ResponseEntity<ReservationResponse> release(@Parameter(description = "Reservation UUID") @PathVariable UUID id) {
        LOGGER.debug("HTTP request to release reservation id={}", id);
        return ResponseEntity.ok(reservationService.release(id));
    }

    /**
     * Consumes a reservation through an atomic business operation.
     */
    @Operation(summary = "Consume reservation", description = "Consumes an active reservation through the business layer.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Reservation consumed"),
            @ApiResponse(responseCode = "404", description = "Reservation not found", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Insufficient stock", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "Reservation rule violation", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PostMapping("/{id}/consume")
    public ResponseEntity<ReservationResponse> consume(@Parameter(description = "Reservation UUID") @PathVariable UUID id) {
        LOGGER.debug("HTTP request to consume reservation id={}", id);
        return ResponseEntity.ok(reservationService.consume(id));
    }

    /**
     * Returns one reservation by UUID.
     */
    @Operation(summary = "Get reservation", description = "Returns one reservation by UUID.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Reservation found"),
            @ApiResponse(responseCode = "404", description = "Reservation not found", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/{id}")
    public ResponseEntity<ReservationResponse> findById(@Parameter(description = "Reservation UUID") @PathVariable UUID id) {
        LOGGER.debug("HTTP request to find reservation id={}", id);
        return ResponseEntity.ok(reservationService.findById(id));
    }

    /**
     * Searches reservations by lifecycle and ownership filters.
     */
    @Operation(summary = "Search reservations", description = "Searches reservations by warehouse, status, project and material.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Reservation page returned"),
            @ApiResponse(responseCode = "400", description = "Invalid filter", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping
    public ResponseEntity<PageResponse<ReservationResponse>> search(
            @Parameter(description = "Warehouse UUID filter") @RequestParam(required = false) UUID warehouseId,
            @Parameter(description = "Reservation status filter", example = "ACTIVE") @RequestParam(required = false) ReservationStatus status,
            @Parameter(description = "Project UUID filter") @RequestParam(required = false) UUID projectId,
            @Parameter(description = "Material UUID filter") @RequestParam(required = false) UUID materialId,
            @PageableDefault(size = 20) Pageable pageable) {
        LOGGER.debug("HTTP request to search reservations");
        return ResponseEntity.ok(reservationService.search(warehouseId, status, projectId, materialId, pageable));
    }

    /**
     * Returns the change history recorded by Hibernate Envers, newest revision first.
     */
    @Operation(summary = "Get reservation change history", description = "Returns the audited change history of one reservation, newest revision first.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Revision page returned"),
            @ApiResponse(responseCode = "404", description = "Reservation not found", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/{id}/revisions")
    public ResponseEntity<PageResponse<EntityRevisionResponse<ReservationResponse>>> revisions(
            @Parameter(description = "Reservation UUID") @PathVariable UUID id,
            @PageableDefault(size = 20) Pageable pageable) {
        LOGGER.debug("HTTP request to read reservation revision history id={}", id);
        return ResponseEntity.ok(reservationService.findRevisions(id, pageable));
    }
}
