package com.alejandro.mtostock.application.mapper;

import com.alejandro.mtostock.application.dto.stock.StockMovementTypeDto;
import com.alejandro.mtostock.infrastructure.persistence.entity.StockMovementType;
import org.mapstruct.Mapper;

/**
 * Maps stock movement type enums between persistence and API layers by stable enum names.
 */
@Mapper(config = MapStructCentralConfig.class)
public interface StockMovementTypeMapper {

    StockMovementTypeDto toDto(StockMovementType type);

    StockMovementType toEntity(StockMovementTypeDto type);
}