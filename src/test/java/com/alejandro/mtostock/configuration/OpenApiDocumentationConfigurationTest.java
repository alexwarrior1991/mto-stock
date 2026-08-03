package com.alejandro.mtostock.configuration;

import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenApiDocumentationConfigurationTest {

    private final OpenAPI openAPI = new OpenApiDocumentationConfiguration().mtoStockOpenApi();

    @Test
    void openApiMetadataServersTagsAndSecurityPlaceholderAreConfigured() {
        assertEquals("Catenary Warehouse Management API", openAPI.getInfo().getTitle());
        assertEquals("v1", openAPI.getInfo().getVersion());
        assertTrue(openAPI.getInfo().getDescription().contains("Railway Catenary Construction Projects"));
        assertEquals(2, openAPI.getServers().size());
        assertTrue(openAPI.getTags().stream().anyMatch(tag -> tag.getName().equals("Materials")));
        assertTrue(openAPI.getTags().stream().anyMatch(tag -> tag.getName().equals("Stock Movements")));
        assertTrue(openAPI.getComponents().getSecuritySchemes().containsKey("bearerAuth"));
        assertTrue(openAPI.getSecurity() == null || openAPI.getSecurity().isEmpty());
    }

    @Test
    void documentedPathsCoverCurrentApiRoadmapWithoutControllers() {
        assertTrue(openAPI.getPaths().containsKey("/api/v1/inventory/materials"));
        assertTrue(openAPI.getPaths().containsKey("/api/v1/inventory/movements/entry"));
        assertTrue(openAPI.getPaths().containsKey("/api/v1/inventory/movements/output"));
        assertTrue(openAPI.getPaths().containsKey("/api/v1/inventory/movements/transfer"));
        assertTrue(openAPI.getPaths().containsKey("/api/v1/inventory/reservations"));
        assertTrue(openAPI.getPaths().containsKey("/api/v1/inventory/assemblies/{id}/availability"));
        assertNotNull(openAPI.getPaths().get("/api/v1/inventory/materials").getGet());
        assertNotNull(openAPI.getPaths().get("/api/v1/inventory/materials").getPost());
    }

    @Test
    void reusableSchemasAndErrorResponsesAreConfigured() {
        assertTrue(openAPI.getComponents().getSchemas().containsKey("ApiErrorResponse"));
        assertTrue(openAPI.getComponents().getSchemas().containsKey("MaterialRequest"));
        assertTrue(openAPI.getComponents().getSchemas().containsKey("StockMovementTransferRequest"));
        assertTrue(openAPI.getComponents().getSchemas().containsKey("AssemblyAvailabilityResponse"));
        assertTrue(openAPI.getComponents().getResponses().containsKey("BadRequest"));
        assertTrue(openAPI.getComponents().getResponses().containsKey("Conflict"));
        assertTrue(openAPI.getComponents().getResponses().containsKey("UnprocessableContent"));
        assertTrue(openAPI.getComponents().getResponses().containsKey("InternalServerError"));
    }

    @Test
    void materialSearchDocumentsPageableAndFilters() {
        var operation = openAPI.getPaths().get("/api/v1/inventory/materials").getGet();

        assertEquals("searchMaterials", operation.getOperationId());
        assertFalse(operation.getParameters().isEmpty());
        assertTrue(operation.getParameters().stream().anyMatch(parameter -> parameter.getName().equals("page")));
        assertTrue(operation.getParameters().stream().anyMatch(parameter -> parameter.getName().equals("size")));
        assertTrue(operation.getParameters().stream().anyMatch(parameter -> parameter.getName().equals("sort")));
        assertTrue(operation.getParameters().stream().anyMatch(parameter -> parameter.getName().equals("warehouseId")));
        assertTrue(operation.getResponses().containsKey("400"));
        assertTrue(operation.getResponses().containsKey("500"));
    }
}