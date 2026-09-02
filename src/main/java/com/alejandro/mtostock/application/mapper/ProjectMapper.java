package com.alejandro.mtostock.application.mapper;

import com.alejandro.mtostock.application.dto.common.PageResponse;
import com.alejandro.mtostock.application.dto.project.ProjectRequest;
import com.alejandro.mtostock.application.dto.project.ProjectResponse;
import com.alejandro.mtostock.application.dto.project.ProjectSummaryResponse;
import com.alejandro.mtostock.application.dto.project.ProjectUpdateRequest;
import com.alejandro.mtostock.infrastructure.persistence.entity.EntityReferenceFactory;
import com.alejandro.mtostock.infrastructure.persistence.entity.Project;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Maps project entities to API DTOs and applies project write DTOs to entities.
 */
@Mapper(config = MapStructCentralConfig.class, uses = {AuditableMapper.class, EntityReferenceFactory.class})
public interface ProjectMapper {

    @Mapping(target = "audit", source = ".")
    ProjectResponse toResponse(Project project);

    ProjectSummaryResponse toSummaryResponse(Project project);

    List<ProjectResponse> toResponseList(List<Project> projects);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "active", constant = "true")
    // Un proyecto creado por la API no viene de ningun sitio: el origen solo lo rellena la
    // sincronizacion de datos maestros, y dejarlo sin mapear a proposito es lo que hace que anadir
    // una columna nueva a la entidad rompa la compilacion en lugar de colarse vacia.
    @Mapping(target = "sourceService", ignore = true)
    @Mapping(target = "sourceEntityId", ignore = true)
    @Mapping(target = "sourceSequenceNumber", ignore = true)
    Project toEntity(ProjectRequest request);

    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "code", source = "code")
    @Mapping(target = "name", source = "name")
    @Mapping(target = "active", source = "active")
    void updateEntity(ProjectUpdateRequest request, @MappingTarget Project project);

    default PageResponse<ProjectResponse> toPageResponse(Page<Project> page) {
        return PageMapper.toPageResponse(page, this::toResponse);
    }
}