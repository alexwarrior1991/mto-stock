package com.alejandro.mtostock.application.service;

import com.alejandro.mtostock.application.dto.audit.EntityRevisionResponse;
import com.alejandro.mtostock.application.dto.common.PageResponse;
import com.alejandro.mtostock.application.dto.project.ProjectRequest;
import com.alejandro.mtostock.application.dto.project.ProjectResponse;
import com.alejandro.mtostock.application.dto.project.ProjectUpdateRequest;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * Application service exposing project catalogue use cases.
 */
public interface ProjectService {

    ProjectResponse create(ProjectRequest request);

    ProjectResponse update(UUID id, ProjectUpdateRequest request);

    ProjectResponse findById(UUID id);

    PageResponse<ProjectResponse> findAll(Pageable pageable);

    /**
     * Historial de cambios del proyecto.
     *
     * <p>Solo recoge los cambios hechos por la API. Los que llegan como evento de datos maestros
     * desde {@code mto-configuration} se escriben con SQL nativo y no dejan revisión — ver
     * {@code docs/07-auditing.md}.</p>
     *
     * @throws com.alejandro.mtostock.application.exception.NotFoundException si no existe
     */
    PageResponse<EntityRevisionResponse<ProjectResponse>> findRevisions(UUID id, Pageable pageable);
}
