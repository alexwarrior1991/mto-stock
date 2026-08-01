package com.alejandro.mtostock.application.mapper;

import com.alejandro.mtostock.application.dto.common.PageResponse;
import com.alejandro.mtostock.application.dto.material.MaterialRequest;
import com.alejandro.mtostock.application.dto.material.MaterialResponse;
import com.alejandro.mtostock.application.dto.material.MaterialSummaryResponse;
import com.alejandro.mtostock.application.dto.material.MaterialUpdateRequest;
import com.alejandro.mtostock.infrastructure.persistence.entity.EntityReferenceFactory;
import com.alejandro.mtostock.infrastructure.persistence.entity.Material;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Maps material catalogue entities to API DTOs and applies material write DTOs to entities.
 */
@Mapper(config = MapStructCentralConfig.class, uses = {AuditableMapper.class, EntityReferenceFactory.class})
public interface MaterialMapper {

    @Mapping(target = "audit", source = ".")
    MaterialResponse toResponse(Material material);

    MaterialSummaryResponse toSummaryResponse(Material material);

    List<MaterialResponse> toResponseList(List<Material> materials);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "active", constant = "true")
    Material toEntity(MaterialRequest request);

    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "code", source = "code")
    @Mapping(target = "name", source = "name")
    @Mapping(target = "unitOfMeasure", source = "unitOfMeasure")
    @Mapping(target = "minimumStockLevel", source = "minimumStockLevel")
    @Mapping(target = "active", source = "active")
    void updateEntity(MaterialUpdateRequest request, @MappingTarget Material material);

    default PageResponse<MaterialResponse> toPageResponse(Page<Material> page) {
        return PageMapper.toPageResponse(page, this::toResponse);
    }
}