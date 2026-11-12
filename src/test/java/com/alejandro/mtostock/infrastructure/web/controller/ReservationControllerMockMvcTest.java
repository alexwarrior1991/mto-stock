package com.alejandro.mtostock.infrastructure.web.controller;

import com.alejandro.mtostock.application.dto.common.PageResponse;
import com.alejandro.mtostock.application.dto.common.PageMetadataResponse;
import com.alejandro.mtostock.application.dto.reservation.ReservationRequest;
import com.alejandro.mtostock.application.dto.reservation.ReservationResponse;
import com.alejandro.mtostock.application.dto.reservation.ReservationStatusDto;
import com.alejandro.mtostock.application.dto.reservation.ReservationUpdateRequest;
import com.alejandro.mtostock.application.exception.IdempotencyKeyConflictException;
import com.alejandro.mtostock.application.exception.InsufficientStockException;
import com.alejandro.mtostock.application.dto.stock.StockMovementOutputRequest;
import com.alejandro.mtostock.application.service.AssemblyService;
import com.alejandro.mtostock.application.service.ReservationService;
import com.alejandro.mtostock.application.service.StockMovementService;
import com.alejandro.mtostock.application.service.WarehouseService;
import com.alejandro.mtostock.infrastructure.persistence.entity.Reservation;
import org.hibernate.query.sqm.PathElementException;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.core.PropertyPath;
import org.springframework.data.core.PropertyReferenceException;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Los otros dos controladores estan para lo que el de reservas no tiene: un parametro obligatorio
// (la disponibilidad de un conjunto) y una referencia externa (los movimientos).
@WebMvcTest({ReservationController.class, AssemblyController.class, StockMovementController.class})
@Import(GlobalExceptionHandler.class)
// Sin la cadena de filtros: lo que se prueba aqui es el contrato HTTP del controlador. Quien decide
// que rol necesita cada verbo es ApiAuthorizationRulesTest, contra controladores sonda.
@AutoConfigureMockMvc(addFilters = false)
class ReservationControllerMockMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReservationService reservationService;

    @MockitoBean
    private AssemblyService assemblyService;

    @MockitoBean
    private StockMovementService stockMovementService;

    @MockitoBean
    private WarehouseService warehouseService;

    @Test
    void createReturnsCreatedLocationAndJsonBody() throws Exception {
        UUID reservationId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        ReservationRequest request = new ReservationRequest(materialId, warehouseId, projectId, new BigDecimal("2.000000"), null);
        when(reservationService.create(request, null)).thenReturn(response(reservationId, materialId, warehouseId, projectId, ReservationStatus.ACTIVE));

        mockMvc.perform(post("/api/v1/inventory/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reservationRequestJson(materialId, warehouseId, projectId, "2.000000")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/inventory/reservations/" + reservationId))
                .andExpect(jsonPath("$.id").value(reservationId.toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void createPassesTheIdempotencyKeyHeaderToTheService() throws Exception {
        UUID reservationId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        ReservationRequest request = new ReservationRequest(materialId, warehouseId, projectId, new BigDecimal("2.000000"), null);
        when(reservationService.create(request, "mto-maintenance:line-1:reserve"))
                .thenReturn(response(reservationId, materialId, warehouseId, projectId, ReservationStatus.ACTIVE));

        mockMvc.perform(post("/api/v1/inventory/reservations")
                        .header("Idempotency-Key", "mto-maintenance:line-1:reserve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reservationRequestJson(materialId, warehouseId, projectId, "2.000000")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/inventory/reservations/" + reservationId))
                .andExpect(jsonPath("$.id").value(reservationId.toString()));
    }

    @Test
    void anIdempotencyKeyReusedWithAnotherBodyIsAConflict() throws Exception {
        UUID materialId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        when(reservationService.create(any(), eq("key-1"))).thenThrow(new IdempotencyKeyConflictException("key-1"));

        mockMvc.perform(post("/api/v1/inventory/reservations")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reservationRequestJson(materialId, warehouseId, projectId, "3.000000")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("IDEM-001"))
                .andExpect(jsonPath("$.message").value("Idempotency key 'key-1' was already used with a different request"));
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

    /**
     * Un {@code sort} que la entidad no tiene lo rechaza Spring Data al montar la consulta
     * ({@link PropertyReferenceException}), y uno que es propiedad de la clase pero no atributo
     * persistente ({@code active} de una reserva, que es un metodo) lo rechaza Hibernate. Los dos son
     * un error de quien llama: 400 y el campo {@code sort}, no el 500 de antes. Que las listas lanzan
     * justo esas dos excepciones lo fija {@code InventoryRepositoryDataJpaTest}.
     */
    @Test
    void aSortTheListCannotOrderByIsABadRequest() throws Exception {
        PropertyReferenceException unknown = assertThrows(PropertyReferenceException.class,
                () -> PropertyPath.from("nope", Reservation.class));
        when(reservationService.search(any(), any(), any(), any(), any()))
                .thenThrow(unknown)
                .thenThrow(new InvalidDataAccessApiUsageException("Could not resolve attribute 'active' of 'Reservation'",
                        new PathElementException("Could not resolve attribute 'active' of 'Reservation'")));

        mockMvc.perform(get("/api/v1/inventory/reservations").param("sort", "nope,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQ-400"))
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("Invalid sort property."))
                .andExpect(jsonPath("$.validationErrors[0].field").value("sort"))
                .andExpect(jsonPath("$.validationErrors[0].message").value("unknown property 'nope'"));
        mockMvc.perform(get("/api/v1/inventory/reservations").param("sort", "active,desc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQ-400"))
                .andExpect(jsonPath("$.message").value("Invalid sort property."))
                .andExpect(jsonPath("$.validationErrors[0].field").value("sort"));
    }

    /**
     * Un mal uso de la API de datos que no viene de un {@code sort} sigue siendo un fallo del
     * servidor, y su mensaje no sale.
     */
    @Test
    void aDataAccessMisuseThatIsNotASortStaysAServerError() throws Exception {
        when(reservationService.search(any(), any(), any(), any(), any()))
                .thenThrow(new InvalidDataAccessApiUsageException("detached entity passed to persist: Reservation"));

        mockMvc.perform(get("/api/v1/inventory/reservations"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("APP-500"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred. Please contact support."));
    }

    /** Un verbo que el recurso no tiene es 405 con {@code Allow}, no 500. */
    @Test
    void aMethodTheResourceDoesNotHaveIsMethodNotAllowedWithItsAllowHeader() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(patch("/api/v1/inventory/reservations/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("GET"),
                        org.hamcrest.Matchers.containsString("PUT"),
                        org.hamcrest.Matchers.containsString("DELETE"))))
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.error").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.errorCode").value("REQ-405"))
                .andExpect(jsonPath("$.method").value("PATCH"));
        mockMvc.perform(delete("/api/v1/inventory/assemblies/{id}", id))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().exists("Allow"))
                .andExpect(jsonPath("$.errorCode").value("REQ-405"));
        verifyNoInteractions(reservationService, assemblyService);
    }

    /** Sin un parametro obligatorio: 400 con su nombre. */
    @Test
    void aMissingRequiredParameterIsABadRequestThatNamesIt() throws Exception {
        mockMvc.perform(get("/api/v1/inventory/assemblies/{id}/availability", UUID.randomUUID()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQ-400"))
                .andExpect(jsonPath("$.message").value("Missing request parameter."))
                .andExpect(jsonPath("$.validationErrors[0].field").value("warehouseId"))
                .andExpect(jsonPath("$.validationErrors[0].message").value("is required"));
    }

    /**
     * Un id que no es un UUID, un enumerado que no existe y una fecha que no lo es son 400 con el
     * nombre del parametro. Una pagina que no es un numero no es un error: Spring Data usa la de por
     * defecto.
     */
    @Test
    void aParameterOfTheWrongTypeIsABadRequestAndAPageThatIsNotANumberIsTheDefaultOne() throws Exception {
        when(reservationService.search(any(), any(), any(), any(), any()))
                .thenReturn(new PageResponse<>(List.of(), new PageMetadataResponse(0, 20, 0, 0, true, true)));

        mockMvc.perform(get("/api/v1/inventory/reservations/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQ-400"))
                .andExpect(jsonPath("$.validationErrors[0].field").value("id"));
        mockMvc.perform(get("/api/v1/inventory/reservations").param("status", "NOPE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQ-400"))
                .andExpect(jsonPath("$.validationErrors[0].field").value("status"));
        mockMvc.perform(get("/api/v1/inventory/movements").param("dateFrom", "yesterday"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQ-400"))
                .andExpect(jsonPath("$.validationErrors[0].field").value("dateFrom"));
        mockMvc.perform(get("/api/v1/inventory/reservations").param("page", "abc").param("size", "-3"))
                .andExpect(status().isOk());
    }

    /** Un cuerpo que no se puede leer es 400, y uno que no es JSON, 415. */
    @Test
    void anUnreadableBodyIsABadRequestAndAnotherMediaTypeIsUnsupported() throws Exception {
        mockMvc.perform(post("/api/v1/inventory/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"materialId\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQ-400"));
        mockMvc.perform(post("/api/v1/inventory/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"materialId\": \"not-a-uuid\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQ-400"));
        mockMvc.perform(post("/api/v1/inventory/reservations")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQ-400"));
        mockMvc.perform(post("/api/v1/inventory/reservations")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.errorCode").value("REQ-415"));
        verifyNoInteractions(reservationService);
    }

    /**
     * Quien solo acepta un formato que la API no da recibe 406, y el error va en JSON igualmente: es
     * el unico cuerpo que hay.
     */
    @Test
    void anAcceptHeaderTheApiCannotServeIsNotAcceptableWithTheJsonError() throws Exception {
        UUID id = UUID.randomUUID();
        when(reservationService.findById(id)).thenReturn(response(id, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), ReservationStatus.ACTIVE));

        mockMvc.perform(get("/api/v1/inventory/reservations/{id}", id).accept(MediaType.APPLICATION_PDF))
                .andExpect(status().isNotAcceptable())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("application/json")))
                .andExpect(jsonPath("$.status").value(406))
                .andExpect(jsonPath("$.errorCode").value("REQ-406"));
    }

    /** Una ruta que no existe bajo la API es 404, con el cuerpo de siempre. */
    @Test
    void aRouteThatDoesNotExistIsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/inventory/nothing-here"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("HTTP-404"));
    }

    /**
     * Una referencia externa en blanco es "sin referencia": llega al servicio como {@code null}, y no
     * a la restriccion {@code chk_stock_movement_external_reference_not_blank} (500).
     */
    @Test
    void aBlankExternalReferenceReachesTheServiceAsNoReference() throws Exception {
        UUID materialId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        when(stockMovementService.registerOutput(any(), any())).thenReturn(null);

        mockMvc.perform(post("/api/v1/inventory/movements/outputs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"materialId": "%s", "warehouseId": "%s", "quantity": 1, "externalReference": "   ", "notes": "kept"}
                        """.formatted(materialId, warehouseId)));

        ArgumentCaptor<StockMovementOutputRequest> request = ArgumentCaptor.forClass(StockMovementOutputRequest.class);
        verify(stockMovementService).registerOutput(request.capture(), any());
        assertNull(request.getValue().externalReference());
        assertEquals("kept", request.getValue().notes());
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