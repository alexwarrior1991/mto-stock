package com.alejandro.mtostock.application.mapper;

import com.alejandro.mtostock.application.dto.common.PageResponse;
import com.alejandro.mtostock.application.dto.warehouse.WarehouseRequest;
import com.alejandro.mtostock.application.dto.warehouse.WarehouseResponse;
import com.alejandro.mtostock.application.dto.warehouse.WarehouseSummaryResponse;
import com.alejandro.mtostock.application.dto.warehouse.WarehouseUpdateRequest;
import com.alejandro.mtostock.infrastructure.persistence.entity.EntityReferenceFactory;
import com.alejandro.mtostock.infrastructure.persistence.entity.Warehouse;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Maps warehouse entities to API DTOs and applies warehouse write DTOs to entities.
 */
@Mapper(config = MapStructCentralConfig.class, uses = {AuditableMapper.class, EntityReferenceFactory.class})
public interface WarehouseMapper {

    @Mapping(target = "audit", source = ".")
    WarehouseResponse toResponse(Warehouse warehouse);

    WarehouseSummaryResponse toSummaryResponse(Warehouse warehouse);

    List<WarehouseResponse> toResponseList(List<Warehouse> warehouses);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "active", constant = "true")
    Warehouse toEntity(WarehouseRequest request);

    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "code", source = "code")
    @Mapping(target = "name", source = "name")
    @Mapping(target = "active", source = "active")
    void updateEntity(WarehouseUpdateRequest request, @MappingTarget Warehouse warehouse);

    default PageResponse<WarehouseResponse> toPageResponse(Page<Warehouse> page) {
        return PageMapper.toPageResponse(page, this::toResponse);
    }
}