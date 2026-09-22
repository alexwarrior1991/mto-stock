package com.alejandro.mtostock.application.service.impl;

import com.alejandro.mtostock.application.dto.audit.EntityRevisionResponse;
import com.alejandro.mtostock.application.dto.common.PageResponse;
import com.alejandro.mtostock.application.dto.project.ProjectRequest;
import com.alejandro.mtostock.application.dto.project.ProjectResponse;
import com.alejandro.mtostock.application.dto.project.ProjectUpdateRequest;
import com.alejandro.mtostock.application.exception.NotFoundException;
import com.alejandro.mtostock.application.exception.ProjectException;
import com.alejandro.mtostock.application.mapper.ProjectMapper;
import com.alejandro.mtostock.application.service.EntityAuditService;
import com.alejandro.mtostock.application.service.InventoryValidationService;
import com.alejandro.mtostock.application.service.ProjectService;
import com.alejandro.mtostock.configuration.cache.CacheInvalidator;
import com.alejandro.mtostock.configuration.cache.CacheNames;
import com.alejandro.mtostock.infrastructure.persistence.entity.Project;
import com.alejandro.mtostock.infrastructure.persistence.repository.ProjectRepository;
import com.alejandro.mtostock.infrastructure.persistence.specification.ProjectSpecification;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Orchestrates project catalogue use cases used by reservations and movements.
 */
@Service
@RequiredArgsConstructor
class ProjectServiceImpl implements ProjectService {

    private static final Logger log = LoggerFactory.getLogger(ProjectServiceImpl.class);

    private final ProjectRepository projectRepository;
    private final ProjectMapper projectMapper;
    private final EntityAuditService entityAuditService;
    private final InventoryValidationService inventoryValidationService;
    private final CacheInvalidator cacheInvalidator;

    @Override
    @Transactional
    public ProjectResponse create(ProjectRequest request) {
        inventoryValidationService.validateProjectCodeIsUnique(request.code(), null);
        Project project = projectRepository.save(projectMapper.toEntity(request));
        log.info("Project created with code {}", project.getCode());
        return projectMapper.toResponse(project);
    }

    @Override
    @Transactional
    public ProjectResponse update(UUID id, ProjectUpdateRequest request) {
        Project project = projectRepository.findById(id).orElseThrow(() -> new NotFoundException("Project", id));
        if (project.isSynchronized()) {
            // Lo que se cambie aqui lo pisa el siguiente evento de datos maestros: el dueno es el origen.
            throw new ProjectException("Project '%s' is synchronized from %s and is not edited through the API"
                    .formatted(project.getCode(), project.getSourceService()));
        }
        inventoryValidationService.validateProjectCodeIsUnique(request.code(), id);
        projectMapper.updateEntity(request, project);
        log.info("Project {} updated", project.getCode());
        cacheInvalidator.evictAfterCommit(CacheNames.PROJECTS, id);
        return projectMapper.toResponse(project);
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CacheNames.PROJECTS, key = "#id")
    public ProjectResponse findById(UUID id) {
        return projectMapper.toResponse(projectRepository.findById(id).orElseThrow(() -> new NotFoundException("Project", id)));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ProjectResponse> search(String search, Boolean active, Pageable pageable) {
        Specification<Project> specification = Specification.where(ProjectSpecification.codeOrNameContains(search))
                .and(ProjectSpecification.activeEquals(active));
        return projectMapper.toPageResponse(projectRepository.findAll(specification, pageable));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<EntityRevisionResponse<ProjectResponse>> findRevisions(UUID id, Pageable pageable) {
        return entityAuditService.findRevisions(
                Project.class,
                id,
                projectMapper::toResponse,
                pageable);
    }
}
