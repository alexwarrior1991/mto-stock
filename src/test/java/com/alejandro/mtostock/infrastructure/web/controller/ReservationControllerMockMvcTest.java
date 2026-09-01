package com.alejandro.mtostock.infrastructure.web.controller;

import com.alejandro.mtostock.application.dto.common.PageResponse;
import com.alejandro.mtostock.application.dto.common.PageMetadataResponse;
import com.alejandro.mtostock.application.dto.reservation.ReservationRequest;
import com.alejandro.mtostock.application.dto.reservation.ReservationResponse;
import com.alejandro.mtostock.application.dto.reservation.ReservationStatusDto;
import com.alejandro.mtostock.application.dto.reservation.ReservationUpdateRequest;
import com.alejandro.mtostock.application.exception.InsufficientStockException;
import com.alejandro.mtostock.application.service.ReservationService;
import com.alejandro.mtostock.infrastructure.persistence.entity.ReservationStatus;
import com.alejandro.mtostock.infrastructure.web.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReservationController.class)
@Import(GlobalExceptionHandler.class)
// Sin la cadena de filtros: lo que se prueba aqui es el contrato HTTP del controlador. Quien decide
// que rol necesita cada verbo es ApiAuthorizationRulesTest, contra controladores sonda.
@AutoConfigureMockMvc(addFilters = false)
class ReservationControllerMockMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReservationService reservationService;

    @Test
    void createReturnsCreatedLocationAndJsonBody() throws Exception {
        UUID reservationId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        ReservationRequest request = new ReservationRequest(materialId, warehouseId, projectId, new BigDecimal("2.000000"), null);
        when(reservationService.create(request)).thenReturn(response(reservationId, materialId, warehouseId, projectId, ReservationStatus.ACTIVE));

        mockMvc.perform(post("/api/v1/inventory/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reservationRequestJson(materialId, warehouseId, projectId, "2.000000")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/inventory/reservations/" + reservationId))
                .andExpect(jsonPath("$.id").value(reservationId.toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void createReturnsValidationErrorsForNegativeQuantity() throws Exception {
        ReservationRequest request = new ReservationRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("-1.000000"), null);

        mockMvc.perform(post("/api/v1/inventory/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reservationRequestJson(request.materialId(), request.warehouseId(), request.projectId(), "-1.000000")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQ-VALIDATION"))
                .andExpect(jsonPath("$.validationErrors", hasSize(1)))
                .andExpect(jsonPath("$.validationErrors[0].field").value("quantity"));
    }

    @Test
    void serviceExceptionsAreRenderedAsStableErrorResponses() throws Exception {
        UUID id = UUID.randomUUID();
        when(reservationService.findById(id)).thenThrow(new com.alejandro.mtostock.application.exception.ReservationException("Reservation cannot be changed."));

        mockMvc.perform(get("/api/v1/inventory/reservations/{id}", id))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.path").value("/api/v1/inventory/reservations/" + id));
    }

    @Test
    void insufficientStockIsReportedAsConflict() throws Exception {
        UUID id = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        when(reservationService.consume(id)).thenThrow(new InsufficientStockException(
                materialId,
                warehouseId,
                new BigDecimal("4.000000"),
                new BigDecimal("1.000000")
        ));

        mockMvc.perform(post("/api/v1/inventory/reservations/{id}/consume", id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Insufficient stock for material %s in warehouse %s: requested 4.000000, available 1.000000".formatted(materialId, warehouseId)));
    }

    @Test
    void searchSupportsPaginationAndJsonStructure() throws Exception {
        UUID reservationId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        ReservationResponse response = response(reservationId, materialId, warehouseId, projectId, ReservationStatus.ACTIVE);
        when(reservationService.search(eq(warehouseId), eq(ReservationStatus.ACTIVE), eq(projectId), eq(materialId), any(PageRequest.class)))
                .thenReturn(new PageResponse<>(List.of(response), new PageMetadataResponse(0, 20, 1, 1, true, true)));

        mockMvc.perform(get("/api/v1/inventory/reservations")
                        .param("warehouseId", warehouseId.toString())
                        .param("status", "ACTIVE")
                        .param("projectId", projectId.toString())
                        .param("materialId", materialId.toString())
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].material.id").value(materialId.toString()));
    }

    @Test
    void updateCancelAndReleaseEndpointsDelegateLifecycleCommands() throws Exception {
        UUID id = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        when(reservationService.update(eq(id), any(ReservationUpdateRequest.class))).thenReturn(response(id, materialId, warehouseId, projectId, ReservationStatus.ACTIVE));
        when(reservationService.cancel(id)).thenReturn(response(id, materialId, warehouseId, projectId, ReservationStatus.CANCELLED));
        when(reservationService.release(id)).thenReturn(response(id, materialId, warehouseId, projectId, ReservationStatus.RELEASED));

        mockMvc.perform(put("/api/v1/inventory/reservations/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reservationUpdateRequestJson(warehouseId, projectId, "3.000000")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        mockMvc.perform(delete("/api/v1/inventory/reservations/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mockMvc.perform(post("/api/v1/inventory/reservations/{id}/release", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"));
    }

    private static ReservationResponse response(UUID reservationId,
                                                UUID materialId,
                                                UUID warehouseId,
                                                UUID projectId,
                                                ReservationStatus status) {
        return new ReservationResponse(
                reservationId,
                new com.alejandro.mtostock.application.dto.material.MaterialSummaryResponse(materialId, "MAT-001", "Material", "unit", true),
                new com.alejandro.mtostock.application.dto.warehouse.WarehouseSummaryResponse(warehouseId, "WH-001", "Warehouse", true),
                new com.alejandro.mtostock.application.dto.project.ProjectSummaryResponse(projectId, "PRJ-001", "Project", true),
                new BigDecimal("2.000000"),
                ReservationStatusDto.valueOf(status.name()),
                Instant.parse("2026-08-01T10:00:00Z"),
                null,
                status == ReservationStatus.ACTIVE,
                null
        );
    }

    private static String reservationRequestJson(UUID materialId, UUID warehouseId, UUID projectId, String quantity) {
        return """
                {
                  "materialId": "%s",
                  "warehouseId": "%s",
                  "projectId": "%s",
                  "quantity": %s
                }
                """.formatted(materialId, warehouseId, projectId, quantity);
    }

    private static String reservationUpdateRequestJson(UUID warehouseId, UUID projectId, String quantity) {
        return """
                {
                  "warehouseId": "%s",
                  "projectId": "%s",
                  "quantity": %s
                }
                """.formatted(warehouseId, projectId, quantity);
    }
}