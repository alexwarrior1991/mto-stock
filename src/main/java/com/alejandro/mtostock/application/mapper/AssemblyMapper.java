package com.alejandro.mtostock.application.mapper;

import com.alejandro.mtostock.application.dto.assembly.AssemblyComponentRequest;
import com.alejandro.mtostock.application.dto.assembly.AssemblyComponentResponse;
import com.alejandro.mtostock.application.dto.assembly.AssemblyRequest;
import com.alejandro.mtostock.application.dto.assembly.AssemblyResponse;
import com.alejandro.mtostock.application.dto.assembly.AssemblySummaryResponse;
import com.alejandro.mtostock.application.dto.assembly.AssemblyUpdateRequest;
import com.alejandro.mtostock.application.dto.common.PageResponse;
import com.alejandro.mtostock.infrastructure.persistence.entity.Assembly;
import com.alejandro.mtostock.infrastructure.persistence.entity.AssemblyComponent;
import com.alejandro.mtostock.infrastructure.persistence.entity.EntityReferenceFactory;
import org.mapstruct.AfterMapping;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Maps assembly entities and bill-of-material component lines to API DTOs.
 */
@Mapper(
        config = MapStructCentralConfig.class,
        uses = {
                AuditableMapper.class,
                MaterialMapper.class,
                EntityReferenceFactory.class
        }
)
public interface AssemblyMapper {

    @Mapping(target = "audit", source = ".")
    AssemblyResponse toResponse(Assembly assembly);

    AssemblySummaryResponse toSummaryResponse(Assembly assembly);

    List<AssemblyResponse> toResponseList(List<Assembly> assemblies);

    @Mapping(target = "audit", source = ".")
    AssemblyComponentResponse toComponentResponse(AssemblyComponent component);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "active", constant = "true")
    Assembly toEntity(AssemblyRequest request);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "assembly", ignore = true)
    @Mapping(target = "material", source = "materialId")
    AssemblyComponent toComponentEntity(AssemblyComponentRequest request);

    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "code", source = "code")
    @Mapping(target = "name", source = "name")
    @Mapping(target = "active", source = "active")
    @Mapping(target = "components", source = "components")
    void updateEntity(AssemblyUpdateRequest request, @MappingTarget Assembly assembly);

    @AfterMapping
    default void linkComponents(@MappingTarget Assembly assembly) {
        if (assembly.getComponents() != null) {
            assembly.getComponents().forEach(component -> component.setAssembly(assembly));
        }
    }

    default PageResponse<AssemblyResponse> toPageResponse(Page<Assembly> page) {
        return PageMapper.toPageResponse(page, this::toResponse);
    }
}