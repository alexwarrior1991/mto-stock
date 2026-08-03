package com.alejandro.mtostock.configuration;

import com.alejandro.mtostock.application.dto.assembly.AssemblyAvailabilityResponse;
import com.alejandro.mtostock.application.dto.assembly.AssemblyRequest;
import com.alejandro.mtostock.application.dto.assembly.AssemblyResponse;
import com.alejandro.mtostock.application.dto.common.PageResponse;
import com.alejandro.mtostock.application.dto.error.ApiErrorResponse;
import com.alejandro.mtostock.application.dto.material.MaterialRequest;
import com.alejandro.mtostock.application.dto.material.MaterialResponse;
import com.alejandro.mtostock.application.dto.material.MaterialStockResponse;
import com.alejandro.mtostock.application.dto.material.MaterialUpdateRequest;
import com.alejandro.mtostock.application.dto.project.ProjectRequest;
import com.alejandro.mtostock.application.dto.project.ProjectResponse;
import com.alejandro.mtostock.application.dto.project.ProjectUpdateRequest;
import com.alejandro.mtostock.application.dto.reservation.ReservationRequest;
import com.alejandro.mtostock.application.dto.reservation.ReservationResponse;
import com.alejandro.mtostock.application.dto.reservation.ReservationUpdateRequest;
import com.alejandro.mtostock.application.dto.stock.StockMovementAdjustmentRequest;
import com.alejandro.mtostock.application.dto.stock.StockMovementEntryRequest;
import com.alejandro.mtostock.application.dto.stock.StockMovementOutputRequest;
import com.alejandro.mtostock.application.dto.stock.StockMovementResponse;
import com.alejandro.mtostock.application.dto.stock.StockMovementTransferRequest;
import com.alejandro.mtostock.application.dto.supplier.SupplierRequest;
import com.alejandro.mtostock.application.dto.supplier.SupplierResponse;
import com.alejandro.mtostock.application.dto.supplier.SupplierUpdateRequest;
import com.alejandro.mtostock.application.dto.warehouse.WarehouseRequest;
import com.alejandro.mtostock.application.dto.warehouse.WarehouseResponse;
import com.alejandro.mtostock.application.dto.warehouse.WarehouseUpdateRequest;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * Defines the public OpenAPI contract for the planned REST API without creating REST controllers.
 */
@Configuration
public class OpenApiDocumentationConfiguration {

    private static final String JSON = "application/json";
    private static final String BEARER_AUTH = "bearerAuth";

    /**
     * Provides API metadata, tags, reusable error responses, schemas, examples and documented paths.
     */
    @Bean
    public OpenAPI mtoStockOpenApi() {
        return new OpenAPI()
                .info(apiInfo())
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("Local development server"),
                        new Server().url("https://api.example.com").description("Production server placeholder")
                ))
                .tags(tags())
                .components(components())
                .paths(paths());
    }

    private static Info apiInfo() {
        return new Info()
                .title("Catenary Warehouse Management API")
                .description("Professional Warehouse Management System for Railway Catenary Construction Projects.")
                .version("v1")
                .termsOfService("https://example.com/terms")
                .contact(new Contact()
                        .name("Catenary Warehouse Platform Team")
                        .email("support@example.com")
                        .url("https://example.com/support"))
                .license(new License()
                        .name("Proprietary")
                        .url("https://example.com/license"));
    }

    private static List<Tag> tags() {
        return List.of(
                tag("Materials", "Material catalogue, stock summary and minimum-stock visibility."),
                tag("Warehouses", "Warehouse catalogue and warehouse-level inventory operations."),
                tag("Stock Movements", "Append-only stock ledger entries, outputs, adjustments and transfers."),
                tag("Assemblies", "Virtual assemblies and bill-of-materials availability calculations."),
                tag("Reservations", "Reservation lifecycle operations that reduce available stock without changing physical stock."),
                tag("Suppliers", "Supplier catalogue used by stock entry operations."),
                tag("Projects", "Project catalogue used by reservations and consumption movements.")
        );
    }

    private static Components components() {
        Components components = new Components()
                .addSecuritySchemes(BEARER_AUTH, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("JWT bearer authentication placeholder. Authentication is not enforced yet."))
                .addResponses("BadRequest", errorResponse("Invalid request, validation failure or malformed JSON.", HttpStatus.BAD_REQUEST))
                .addResponses("NotFound", errorResponse("The requested resource or business aggregate was not found.", HttpStatus.NOT_FOUND))
                .addResponses("Conflict", errorResponse("The request conflicts with current business state, such as duplicate codes or insufficient stock.", HttpStatus.CONFLICT))
                .addResponses("UnprocessableContent", errorResponse("The request is syntactically valid but violates a domain rule.", HttpStatus.UNPROCESSABLE_CONTENT))
                .addResponses("InternalServerError", errorResponse("Unexpected server error. Internal details are not exposed to clients.", HttpStatus.INTERNAL_SERVER_ERROR));
        addSchemas(components);
        return components;
    }

    private static Paths paths() {
        return new Paths()
                .addPathItem("/api/v1/inventory/materials", new PathItem()
                        .get(searchOperation("Materials", "searchMaterials", "Search materials", "Searches the material catalogue using composable filters.", MaterialResponse.class,
                                List.of(query("code", "string", "Material code filter.", "MAT-COPPER-50"),
                                        query("name", "string", "Material name or description filter.", "Copper"),
                                        query("active", "boolean", "Whether only active or inactive materials should be returned.", "true"),
                                        query("warehouseId", "string", "Warehouse UUID used by stock-related filters.", "018f60be-1b9a-7cc3-8c6b-2f93e8c6a001"),
                                        query("belowMinimum", "boolean", "Returns materials whose available stock is below the configured minimum.", "false"))))
                        .post(createOperation("Materials", "createMaterial", "Create material", "Creates a catalogue material. Material codes must be unique.", MaterialRequest.class, MaterialResponse.class, materialCreateExample())))
                .addPathItem("/api/v1/inventory/materials/{id}", new PathItem()
                        .get(findOperation("Materials", "findMaterialById", "Get material", "Returns one material by UUID.", MaterialResponse.class))
                        .put(updateOperation("Materials", "updateMaterial", "Update material", "Updates an existing material without changing audit metadata.", MaterialUpdateRequest.class, MaterialResponse.class))
                        .delete(deleteOperation("Materials", "deleteMaterial", "Delete material", "Deletes or deactivates a material when no inventory rule prevents it.")))
                .addPathItem("/api/v1/inventory/materials/{id}/stock", new PathItem()
                        .get(operation("Materials", "getMaterialStock", "Get material stock", "Calculates physical, reserved and available stock from movements and reservations only.")
                                .addParametersItem(pathId())
                                .addParametersItem(query("warehouseId", "string", "Optional warehouse UUID. Omit it for global stock.", "018f60be-1b9a-7cc3-8c6b-2f93e8c6a001"))
                                .responses(okWithErrors(MaterialStockResponse.class))))
                .addPathItem("/api/v1/inventory/warehouses", cataloguePath("Warehouses", "Warehouse", WarehouseRequest.class, WarehouseResponse.class, warehouseCreateExample()))
                .addPathItem("/api/v1/inventory/warehouses/{id}", catalogueItemPath("Warehouses", "Warehouse", WarehouseUpdateRequest.class, WarehouseResponse.class))
                .addPathItem("/api/v1/inventory/suppliers", cataloguePath("Suppliers", "Supplier", SupplierRequest.class, SupplierResponse.class, supplierCreateExample()))
                .addPathItem("/api/v1/inventory/suppliers/{id}", catalogueItemPath("Suppliers", "Supplier", SupplierUpdateRequest.class, SupplierResponse.class))
                .addPathItem("/api/v1/inventory/projects", cataloguePath("Projects", "Project", ProjectRequest.class, ProjectResponse.class, projectCreateExample()))
                .addPathItem("/api/v1/inventory/projects/{id}", catalogueItemPath("Projects", "Project", ProjectUpdateRequest.class, ProjectResponse.class))
                .addPathItem("/api/v1/inventory/assemblies", new PathItem()
                        .get(searchOperation("Assemblies", "searchAssemblies", "Search assemblies", "Searches virtual assemblies and BOM definitions.", AssemblyResponse.class,
                                List.of(query("code", "string", "Assembly code filter.", "ASM-CAT-001"),
                                        query("name", "string", "Assembly name or description filter.", "Bracket"),
                                        query("active", "boolean", "Whether only active or inactive assemblies should be returned.", "true"))))
                        .post(createOperation("Assemblies", "createAssembly", "Create assembly", "Creates a virtual assembly with its bill of materials.", AssemblyRequest.class, AssemblyResponse.class, assemblyCreateExample())))
                .addPathItem("/api/v1/inventory/assemblies/{id}", new PathItem()
                        .get(findOperation("Assemblies", "findAssemblyById", "Get assembly", "Returns one assembly and its BOM components.", AssemblyResponse.class)))
                .addPathItem("/api/v1/inventory/assemblies/{id}/availability", new PathItem()
                        .get(operation("Assemblies", "calculateAssemblyAvailability", "Calculate assembly availability", "Calculates maximum producible quantity, limiting component and missing quantities from component stock.")
                                .addParametersItem(pathId())
                                .addParametersItem(query("warehouseId", "string", "Warehouse UUID used for component stock availability.", "018f60be-1b9a-7cc3-8c6b-2f93e8c6a001"))
                                .responses(okWithErrors(AssemblyAvailabilityResponse.class))))
                .addPathItem("/api/v1/inventory/stock", new PathItem()
                        .get(searchOperation("Stock Movements", "searchStockMovements", "Search stock movements", "Searches the append-only stock ledger with date, material, warehouse, project and user filters.", StockMovementResponse.class,
                                List.of(query("movementType", "string", "Movement type filter.", "ENTRY"),
                                        query("warehouseId", "string", "Warehouse UUID filter.", "018f60be-1b9a-7cc3-8c6b-2f93e8c6a001"),
                                        query("projectId", "string", "Project UUID filter.", "018f60be-1b9a-7cc3-8c6b-2f93e8c6a010"),
                                        query("materialId", "string", "Material UUID filter.", "018f60be-1b9a-7cc3-8c6b-2f93e8c6a020"),
                                        query("dateFrom", "string", "Inclusive movement date-time lower bound.", "2026-08-01T00:00:00Z"),
                                        query("dateTo", "string", "Inclusive movement date-time upper bound.", "2026-08-31T23:59:59Z"),
                                        query("user", "string", "Audit user filter.", "warehouse.operator")))))
                .addPathItem("/api/v1/inventory/stock/low", new PathItem()
                        .get(operation("Materials", "findLowStockMaterials", "Find low stock materials", "Returns materials whose calculated available stock is below the configured minimum level.")
                                .addParametersItem(query("warehouseId", "string", "Optional warehouse UUID. Omit it for global stock.", "018f60be-1b9a-7cc3-8c6b-2f93e8c6a001"))
                                .addParametersItem(page()).addParametersItem(size()).addParametersItem(sort())
                                .responses(okPageWithErrors(MaterialResponse.class))))
                .addPathItem("/api/v1/inventory/movements/entry", movementPath("registerStockEntry", "Register stock entry", "Creates a positive stock entry movement, optionally linked to a supplier.", StockMovementEntryRequest.class, stockEntryExample()))
                .addPathItem("/api/v1/inventory/movements/output", movementPath("registerStockOutput", "Register stock output", "Creates a negative stock output movement, optionally linked to a project or reservation.", StockMovementOutputRequest.class, stockOutputExample()))
                .addPathItem("/api/v1/inventory/movements/adjustment", movementPath("registerStockAdjustment", "Register stock adjustment", "Creates a positive or negative stock adjustment movement.", StockMovementAdjustmentRequest.class, stockAdjustmentExample()))
                .addPathItem("/api/v1/inventory/movements/transfer", movementPath("registerStockTransfer", "Transfer stock", "Atomically creates outgoing and incoming transfer movements.", StockMovementTransferRequest.class, stockTransferExample()))
                .addPathItem("/api/v1/inventory/reservations", new PathItem()
                        .get(searchOperation("Reservations", "searchReservations", "Search reservations", "Searches reservations by warehouse, status, project and material.", ReservationResponse.class,
                                List.of(query("warehouseId", "string", "Warehouse UUID filter.", "018f60be-1b9a-7cc3-8c6b-2f93e8c6a001"),
                                        query("status", "string", "Reservation status filter.", "ACTIVE"),
                                        query("projectId", "string", "Project UUID filter.", "018f60be-1b9a-7cc3-8c6b-2f93e8c6a010"),
                                        query("materialId", "string", "Material UUID filter.", "018f60be-1b9a-7cc3-8c6b-2f93e8c6a020"))))
                        .post(createOperation("Reservations", "createReservation", "Create reservation", "Reserves available stock without modifying physical stock.", ReservationRequest.class, ReservationResponse.class, reservationCreateExample())))
                .addPathItem("/api/v1/inventory/reservations/{id}", new PathItem()
                        .get(findOperation("Reservations", "findReservationById", "Get reservation", "Returns one reservation by UUID.", ReservationResponse.class))
                        .put(updateOperation("Reservations", "updateReservation", "Update reservation", "Updates an active reservation when lifecycle rules allow it.", ReservationUpdateRequest.class, ReservationResponse.class))
                        .delete(deleteOperation("Reservations", "cancelReservation", "Cancel reservation", "Cancels an active reservation and releases reserved quantity.")));
    }

    private static PathItem cataloguePath(String tag, String aggregate, Class<?> requestType, Class<?> responseType, String example) {
        String lower = aggregate.toLowerCase();
        return new PathItem()
                .get(operation(tag, "list" + aggregate + "s", "List " + lower + "s", "Returns a pageable list of " + lower + " catalogue records.")
                        .addParametersItem(page()).addParametersItem(size()).addParametersItem(sort())
                        .responses(okPageWithErrors(responseType)))
                .post(createOperation(tag, "create" + aggregate, "Create " + lower, "Creates a " + lower + " catalogue record.", requestType, responseType, example));
    }

    private static PathItem catalogueItemPath(String tag, String aggregate, Class<?> requestType, Class<?> responseType) {
        String lower = aggregate.toLowerCase();
        return new PathItem()
                .get(findOperation(tag, "find" + aggregate + "ById", "Get " + lower, "Returns one " + lower + " by UUID.", responseType))
                .put(updateOperation(tag, "update" + aggregate, "Update " + lower, "Updates an existing " + lower + " catalogue record.", requestType, responseType))
                .delete(deleteOperation(tag, "delete" + aggregate, "Delete " + lower, "Deletes or deactivates the " + lower + " when business rules allow it."));
    }

    private static PathItem movementPath(String operationId, String summary, String description, Class<?> requestType, String example) {
        return new PathItem().post(createOperation("Stock Movements", operationId, summary, description, requestType, StockMovementResponse.class, example));
    }

    private static Operation searchOperation(String tag, String operationId, String summary, String description, Class<?> responseType, List<Parameter> filters) {
        Operation operation = operation(tag, operationId, summary, description);
        filters.forEach(operation::addParametersItem);
        return operation.addParametersItem(page()).addParametersItem(size()).addParametersItem(sort()).responses(okPageWithErrors(responseType));
    }

    private static Operation findOperation(String tag, String operationId, String summary, String description, Class<?> responseType) {
        return operation(tag, operationId, summary, description).addParametersItem(pathId()).responses(okWithErrors(responseType));
    }

    private static Operation createOperation(String tag, String operationId, String summary, String description, Class<?> requestType, Class<?> responseType, String example) {
        return operation(tag, operationId, summary, description)
                .requestBody(jsonRequest(requestType, example))
                .responses(new ApiResponses()
                        .addApiResponse("201", response("Created.", responseType))
                        .addApiResponse("400", refResponse("BadRequest"))
                        .addApiResponse("404", refResponse("NotFound"))
                        .addApiResponse("409", refResponse("Conflict"))
                        .addApiResponse("422", refResponse("UnprocessableContent"))
                        .addApiResponse("500", refResponse("InternalServerError")));
    }

    private static Operation updateOperation(String tag, String operationId, String summary, String description, Class<?> requestType, Class<?> responseType) {
        return operation(tag, operationId, summary, description)
                .addParametersItem(pathId())
                .requestBody(jsonRequest(requestType, null))
                .responses(okWithErrors(responseType));
    }

    private static Operation deleteOperation(String tag, String operationId, String summary, String description) {
        return operation(tag, operationId, summary, description)
                .addParametersItem(pathId())
                .responses(new ApiResponses()
                        .addApiResponse("204", new ApiResponse().description("Deleted or cancelled successfully. No content is returned."))
                        .addApiResponse("400", refResponse("BadRequest"))
                        .addApiResponse("404", refResponse("NotFound"))
                        .addApiResponse("409", refResponse("Conflict"))
                        .addApiResponse("422", refResponse("UnprocessableContent"))
                        .addApiResponse("500", refResponse("InternalServerError")));
    }

    private static Operation operation(String tag, String operationId, String summary, String description) {
        return new Operation()
                .operationId(operationId)
                .addTagsItem(tag)
                .summary(summary)
                .description(description);
    }

    private static ApiResponses okWithErrors(Class<?> responseType) {
        return new ApiResponses()
                .addApiResponse("200", response("Successful response.", responseType))
                .addApiResponse("400", refResponse("BadRequest"))
                .addApiResponse("404", refResponse("NotFound"))
                .addApiResponse("409", refResponse("Conflict"))
                .addApiResponse("422", refResponse("UnprocessableContent"))
                .addApiResponse("500", refResponse("InternalServerError"));
    }

    private static ApiResponses okPageWithErrors(Class<?> responseType) {
        return new ApiResponses()
                .addApiResponse("200", pageResponse("Successful pageable response containing " + responseType.getSimpleName() + " items."))
                .addApiResponse("400", refResponse("BadRequest"))
                .addApiResponse("404", refResponse("NotFound"))
                .addApiResponse("409", refResponse("Conflict"))
                .addApiResponse("422", refResponse("UnprocessableContent"))
                .addApiResponse("500", refResponse("InternalServerError"));
    }

    private static RequestBody jsonRequest(Class<?> requestType, String example) {
        MediaType mediaType = new MediaType().schema(schemaRef(requestType));
        if (example != null) {
            mediaType.addExamples("default", new Example().summary("Example request").value(example));
        }
        return new RequestBody()
                .required(true)
                .description("JSON command payload.")
                .content(new Content().addMediaType(JSON, mediaType));
    }

    private static ApiResponse response(String description, Class<?> responseType) {
        return new ApiResponse()
                .description(description)
                .content(new Content().addMediaType(JSON, new MediaType().schema(schemaRef(responseType))));
    }

    private static ApiResponse pageResponse(String description) {
        return new ApiResponse()
                .description(description)
                .content(new Content().addMediaType(JSON, new MediaType().schema(schemaRef(PageResponse.class))));
    }

    private static ApiResponse errorResponse(String description, HttpStatus status) {
        return new ApiResponse()
                .description(description)
                .content(new Content().addMediaType(JSON, new MediaType()
                        .schema(schemaRef(ApiErrorResponse.class))
                        .addExamples("error", new Example().summary(status.value() + " " + status.getReasonPhrase()).value(errorExample(status)))));
    }

    private static ApiResponse refResponse(String name) {
        return new ApiResponse().$ref("#/components/responses/" + name);
    }

    private static Schema<?> schemaRef(Class<?> type) {
        return new Schema<>().$ref("#/components/schemas/" + type.getSimpleName());
    }

    private static Parameter pathId() {
        return new Parameter()
                .name("id")
                .in("path")
                .required(true)
                .description("Resource UUID.")
                .schema(new Schema<>().type("string").format("uuid"))
                .example("018f60be-1b9a-7cc3-8c6b-2f93e8c6a001");
    }

    private static Parameter page() {
        return query("page", "integer", "Zero-based page index.", "0");
    }

    private static Parameter size() {
        return query("size", "integer", "Maximum number of items per page.", "20");
    }

    private static Parameter sort() {
        return query("sort", "string", "Sort expression in the format property,direction. Repeatable by clients.", "code,asc");
    }

    private static Parameter query(String name, String type, String description, String example) {
        return new Parameter()
                .name(name)
                .in("query")
                .required(false)
                .description(description)
                .schema(new Schema<>().type(type))
                .example(example);
    }

    private static Tag tag(String name, String description) {
        return new Tag().name(name).description(description);
    }

    private static void addSchemas(Components components) {
        List<Class<?>> schemaTypes = List.of(
                ApiErrorResponse.class,
                PageResponse.class,
                MaterialRequest.class,
                MaterialUpdateRequest.class,
                MaterialResponse.class,
                MaterialStockResponse.class,
                WarehouseRequest.class,
                WarehouseUpdateRequest.class,
                WarehouseResponse.class,
                SupplierRequest.class,
                SupplierUpdateRequest.class,
                SupplierResponse.class,
                ProjectRequest.class,
                ProjectUpdateRequest.class,
                ProjectResponse.class,
                AssemblyRequest.class,
                AssemblyResponse.class,
                AssemblyAvailabilityResponse.class,
                StockMovementEntryRequest.class,
                StockMovementOutputRequest.class,
                StockMovementAdjustmentRequest.class,
                StockMovementTransferRequest.class,
                StockMovementResponse.class,
                ReservationRequest.class,
                ReservationUpdateRequest.class,
                ReservationResponse.class
        );
        schemaTypes.forEach(type -> {
            var resolvedSchema = ModelConverters.getInstance().readAllAsResolvedSchema(type);
            if (resolvedSchema.schema != null) {
                components.addSchemas(type.getSimpleName(), resolvedSchema.schema.description(schemaDescription(type)));
            }
            if (resolvedSchema.referencedSchemas != null) {
                resolvedSchema.referencedSchemas.forEach(components::addSchemas);
            }
        });
    }

    private static String schemaDescription(Class<?> type) {
        return switch (type.getSimpleName()) {
            case "ApiErrorResponse" -> "Standard error response returned by the global exception handler.";
            case "PageResponse" -> "Generic pageable response wrapper with content and pagination metadata.";
            default -> "DTO schema for " + type.getSimpleName() + ". Bean Validation annotations define required fields and value limits.";
        };
    }

    private static String materialCreateExample() {
        return """
                {
                  "code": "MAT-COPPER-50",
                  "name": "Copper catenary wire 50 mm2",
                  "unitOfMeasure": "m",
                  "minimumStockLevel": 250.000000
                }
                """;
    }

    private static String warehouseCreateExample() {
        return """
                {
                  "code": "WH-MAD-01",
                  "name": "Madrid central warehouse",
                  "active": true
                }
                """;
    }

    private static String supplierCreateExample() {
        return """
                {
                  "code": "SUP-CAT-001",
                  "name": "Catenary Components Europe",
                  "active": true
                }
                """;
    }

    private static String projectCreateExample() {
        return """
                {
                  "code": "PRJ-AVE-2026-001",
                  "name": "High-speed catenary renewal section A",
                  "active": true
                }
                """;
    }

    private static String assemblyCreateExample() {
        return """
                {
                  "code": "ASM-BRACKET-001",
                  "name": "Catenary support bracket kit",
                  "active": true,
                  "components": [
                    { "materialId": "018f60be-1b9a-7cc3-8c6b-2f93e8c6a020", "quantity": 2.000000 },
                    { "materialId": "018f60be-1b9a-7cc3-8c6b-2f93e8c6a021", "quantity": 4.000000 }
                  ]
                }
                """;
    }

    private static String stockEntryExample() {
        return """
                {
                  "materialId": "018f60be-1b9a-7cc3-8c6b-2f93e8c6a020",
                  "warehouseId": "018f60be-1b9a-7cc3-8c6b-2f93e8c6a001",
                  "supplierId": "018f60be-1b9a-7cc3-8c6b-2f93e8c6a030",
                  "quantity": 500.000000,
                  "documentReference": "DEL-2026-00045",
                  "notes": "Supplier delivery for phase 1"
                }
                """;
    }

    private static String stockOutputExample() {
        return """
                {
                  "materialId": "018f60be-1b9a-7cc3-8c6b-2f93e8c6a020",
                  "warehouseId": "018f60be-1b9a-7cc3-8c6b-2f93e8c6a001",
                  "projectId": "018f60be-1b9a-7cc3-8c6b-2f93e8c6a010",
                  "quantity": 120.000000,
                  "documentReference": "OUT-2026-00018",
                  "notes": "Issued to installation crew"
                }
                """;
    }

    private static String stockAdjustmentExample() {
        return """
                {
                  "materialId": "018f60be-1b9a-7cc3-8c6b-2f93e8c6a020",
                  "warehouseId": "018f60be-1b9a-7cc3-8c6b-2f93e8c6a001",
                  "direction": "POSITIVE",
                  "quantity": 5.000000,
                  "documentReference": "ADJ-2026-00007",
                  "notes": "Inventory count correction"
                }
                """;
    }

    private static String stockTransferExample() {
        return """
                {
                  "materialId": "018f60be-1b9a-7cc3-8c6b-2f93e8c6a020",
                  "sourceWarehouseId": "018f60be-1b9a-7cc3-8c6b-2f93e8c6a001",
                  "targetWarehouseId": "018f60be-1b9a-7cc3-8c6b-2f93e8c6a002",
                  "quantity": 80.000000,
                  "documentReference": "TRF-2026-00011",
                  "notes": "Rebalance stock for project start"
                }
                """;
    }

    private static String reservationCreateExample() {
        return """
                {
                  "materialId": "018f60be-1b9a-7cc3-8c6b-2f93e8c6a020",
                  "warehouseId": "018f60be-1b9a-7cc3-8c6b-2f93e8c6a001",
                  "projectId": "018f60be-1b9a-7cc3-8c6b-2f93e8c6a010",
                  "quantity": 60.000000,
                  "expiresAt": "2026-09-15T23:59:59Z",
                  "notes": "Reserved for weekend installation window"
                }
                """;
    }

    private static String errorExample(HttpStatus status) {
        String errorCode = switch (status.value()) {
            case 400 -> "REQ-VALIDATION";
            case 404 -> "MAT-404";
            case 409 -> "MAT-409";
            case 422 -> "RES-001";
            default -> "APP-500";
        };
        String message = status.is5xxServerError() ? "An unexpected error occurred. Please contact support." : "Request could not be processed.";
        return """
                {
                  "timestamp": "2026-08-03T09:53:00Z",
                  "status": %d,
                  "error": "%s",
                  "message": "%s",
                  "path": "/api/v1/inventory/materials/018f60be-1b9a-7cc3-8c6b-2f93e8c6a001",
                  "method": "GET",
                  "errorCode": "%s",
                  "correlationId": "corr-20260803-0001",
                  "validationErrors": []
                }
                """.formatted(status.value(), status.name(), message, errorCode);
    }
}