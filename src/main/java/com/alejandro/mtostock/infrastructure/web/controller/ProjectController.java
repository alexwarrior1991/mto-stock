package com.alejandro.mtostock.infrastructure.web.controller;

import com.alejandro.mtostock.application.dto.common.PageResponse;
import com.alejandro.mtostock.application.dto.error.ApiErrorResponse;
import com.alejandro.mtostock.application.dto.project.ProjectRequest;
import com.alejandro.mtostock.application.dto.project.ProjectResponse;
import com.alejandro.mtostock.application.dto.project.ProjectUpdateRequest;
import com.alejandro.mtostock.application.service.ProjectService;
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
 * REST controller exposing project catalogue use cases.
 */
@Validated
@RestController
@RequestMapping("/api/v1/inventory/projects")
@Tag(name = "Projects", description = "Project catalogue used by reservations and consumption movements.")
public class ProjectController {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProjectController.class);

    private final ProjectService projectService;
    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    /**
     * Creates a project and returns HTTP 201.
     */
    @Operation(summary = "Create project", description = "Creates a project catalogue record.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Project created"),
            @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Duplicate project code", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PostMapping
    public ResponseEntity<ProjectResponse> create(@Valid @RequestBody ProjectRequest request) {
        LOGGER.debug("HTTP request to create project code={}", request.code());
        ProjectResponse response = projectService.create(request);
        return ResponseEntity.created(URI.create("/api/v1/inventory/projects/" + response.id())).body(response);
    }

    /**
     * Updates a project.
     */
    @Operation(summary = "Update project", description = "Updates an existing project catalogue record.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Project updated"),
            @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Project not found", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PutMapping("/{id}")
    public ResponseEntity<ProjectResponse> update(@Parameter(description = "Project UUID") @PathVariable UUID id,
                                                 @Valid @RequestBody ProjectUpdateRequest request) {
        LOGGER.debug("HTTP request to update project id={}", id);
        return ResponseEntity.ok(projectService.update(id, request));
    }

    /**
     * Returns one project by UUID.
     */
    @Operation(summary = "Get project", description = "Returns one project by UUID.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Project found"),
            @ApiResponse(responseCode = "404", description = "Project not found", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/{id}")
    public ResponseEntity<ProjectResponse> findById(@Parameter(description = "Project UUID") @PathVariable UUID id) {
        LOGGER.debug("HTTP request to find project id={}", id);
        return ResponseEntity.ok(projectService.findById(id));
    }

    /**
     * Lists projects with pageable/sort parameters.
     */
    @Operation(summary = "List projects", description = "Returns a pageable project list.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Project page returned"),
            @ApiResponse(responseCode = "500", description = "Unexpected server error", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping
    public ResponseEntity<PageResponse<ProjectResponse>> findAll(@PageableDefault(size = 20) Pageable pageable) {
        LOGGER.debug("HTTP request to list projects");
        return ResponseEntity.ok(projectService.findAll(pageable));
    }
}