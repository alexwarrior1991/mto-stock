package com.alejandro.mtostock.application.mapper;

import com.alejandro.mtostock.application.dto.reservation.ReservationStatusDto;
import com.alejandro.mtostock.infrastructure.persistence.entity.ReservationStatus;
import org.mapstruct.Mapper;

/**
 * Maps reservation status enums between persistence and API layers by stable enum names.
 */
@Mapper(config = MapStructCentralConfig.class)
public interface ReservationStatusMapper {

    ReservationStatusDto toDto(ReservationStatus status);

    ReservationStatus toEntity(ReservationStatusDto status);
}