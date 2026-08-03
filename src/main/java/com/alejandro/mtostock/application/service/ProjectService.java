package com.alejandro.mtostock.application.service;

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
}