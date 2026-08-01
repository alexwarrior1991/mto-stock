package com.alejandro.mtostock.application.mapper;

import com.alejandro.mtostock.application.dto.common.PageResponse;
import com.alejandro.mtostock.application.dto.reservation.ReservationRequest;
import com.alejandro.mtostock.application.dto.reservation.ReservationResponse;
import com.alejandro.mtostock.application.dto.reservation.ReservationStatusUpdateRequest;
import com.alejandro.mtostock.application.dto.reservation.ReservationSummaryResponse;
import com.alejandro.mtostock.application.dto.reservation.ReservationUpdateRequest;
import com.alejandro.mtostock.infrastructure.persistence.entity.EntityReferenceFactory;
import com.alejandro.mtostock.infrastructure.persistence.entity.Reservation;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Maps reservation entities to API DTOs and applies reservation request DTOs to existing entities.
 */
@Mapper(
        config = MapStructCentralConfig.class,
        uses = {
                AuditableMapper.class,
                MaterialMapper.class,
                WarehouseMapper.class,
                ProjectMapper.class,
                ReservationStatusMapper.class,
                EntityReferenceFactory.class
        }
)
public interface ReservationMapper {

    @Mapping(target = "active", expression = "java(reservation.isActive())")
    @Mapping(target = "audit", source = ".")
    ReservationResponse toResponse(Reservation reservation);

    ReservationSummaryResponse toSummaryResponse(Reservation reservation);

    List<ReservationResponse> toResponseList(List<Reservation> reservations);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "material", source = "materialId")
    @Mapping(target = "warehouse", source = "warehouseId")
    @Mapping(target = "project", source = "projectId")
    @Mapping(target = "status", constant = "ACTIVE")
    @Mapping(target = "releasedAt", ignore = true)
    Reservation toEntity(ReservationRequest request);

    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "warehouse", source = "warehouseId")
    @Mapping(target = "project", source = "projectId")
    @Mapping(target = "quantity", source = "quantity")
    void updateEntity(ReservationUpdateRequest request, @MappingTarget Reservation reservation);

    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "status", source = "status")
    @Mapping(target = "releasedAt", source = "releasedAt")
    void updateStatus(ReservationStatusUpdateRequest request, @MappingTarget Reservation reservation);

    default PageResponse<ReservationResponse> toPageResponse(Page<Reservation> page) {
        return PageMapper.toPageResponse(page, this::toResponse);
    }
}