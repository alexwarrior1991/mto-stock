package com.alejandro.mtostock.application.mapper;

import com.alejandro.mtostock.application.dto.common.PageResponse;
import com.alejandro.mtostock.application.dto.supplier.SupplierRequest;
import com.alejandro.mtostock.application.dto.supplier.SupplierResponse;
import com.alejandro.mtostock.application.dto.supplier.SupplierSummaryResponse;
import com.alejandro.mtostock.application.dto.supplier.SupplierUpdateRequest;
import com.alejandro.mtostock.infrastructure.persistence.entity.EntityReferenceFactory;
import com.alejandro.mtostock.infrastructure.persistence.entity.Supplier;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Maps supplier entities to API DTOs and applies supplier write DTOs to entities.
 */
@Mapper(config = MapStructCentralConfig.class, uses = {AuditableMapper.class, EntityReferenceFactory.class})
public interface SupplierMapper {

    @Mapping(target = "audit", source = ".")
    SupplierResponse toResponse(Supplier supplier);

    SupplierSummaryResponse toSummaryResponse(Supplier supplier);

    List<SupplierResponse> toResponseList(List<Supplier> suppliers);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "active", constant = "true")
    Supplier toEntity(SupplierRequest request);

    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "code", source = "code")
    @Mapping(target = "name", source = "name")
    @Mapping(target = "active", source = "active")
    void updateEntity(SupplierUpdateRequest request, @MappingTarget Supplier supplier);

    default PageResponse<SupplierResponse> toPageResponse(Page<Supplier> page) {
        return PageMapper.toPageResponse(page, this::toResponse);
    }
}