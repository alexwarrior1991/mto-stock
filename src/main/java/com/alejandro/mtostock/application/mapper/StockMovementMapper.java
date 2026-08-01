package com.alejandro.mtostock.application.mapper;

import com.alejandro.mtostock.application.dto.common.PageResponse;
import com.alejandro.mtostock.application.dto.stock.StockAdjustmentDirection;
import com.alejandro.mtostock.application.dto.stock.StockMovementAdjustmentRequest;
import com.alejandro.mtostock.application.dto.stock.StockMovementEntryRequest;
import com.alejandro.mtostock.application.dto.stock.StockMovementOutputRequest;
import com.alejandro.mtostock.application.dto.stock.StockMovementResponse;
import com.alejandro.mtostock.application.dto.stock.StockMovementSummaryResponse;
import com.alejandro.mtostock.application.dto.stock.StockMovementTransferRequest;
import com.alejandro.mtostock.infrastructure.persistence.entity.EntityReferenceFactory;
import com.alejandro.mtostock.infrastructure.persistence.entity.StockMovement;
import com.alejandro.mtostock.infrastructure.persistence.entity.StockMovementType;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Maps append-only stock movement ledger entities and command DTOs for entries, outputs, adjustments, and transfers.
 */
@Mapper(
        config = MapStructCentralConfig.class,
        uses = {
                AuditableMapper.class,
                MaterialMapper.class,
                WarehouseMapper.class,
                SupplierMapper.class,
                ProjectMapper.class,
                ReservationMapper.class,
                StockMovementTypeMapper.class,
                EntityReferenceFactory.class
        }
)
public interface StockMovementMapper {

    @Mapping(target = "signedQuantity", expression = "java(stockMovement.signedQuantity())")
    @Mapping(target = "audit", source = ".")
    StockMovementResponse toResponse(StockMovement stockMovement);

    StockMovementSummaryResponse toSummaryResponse(StockMovement stockMovement);

    List<StockMovementResponse> toResponseList(List<StockMovement> stockMovements);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "material", source = "materialId")
    @Mapping(target = "warehouse", source = "warehouseId")
    @Mapping(target = "supplier", source = "supplierId")
    @Mapping(target = "type", constant = "ENTRY")
    @Mapping(target = "project", ignore = true)
    @Mapping(target = "reservation", ignore = true)
    @Mapping(target = "relatedMovement", ignore = true)
    StockMovement toEntryEntity(StockMovementEntryRequest request);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "material", source = "materialId")
    @Mapping(target = "warehouse", source = "warehouseId")
    @Mapping(target = "project", source = "projectId")
    @Mapping(target = "reservation", source = "reservationId")
    @Mapping(target = "type", constant = "OUTPUT")
    @Mapping(target = "supplier", ignore = true)
    @Mapping(target = "relatedMovement", ignore = true)
    StockMovement toOutputEntity(StockMovementOutputRequest request);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "material", source = "materialId")
    @Mapping(target = "warehouse", source = "warehouseId")
    @Mapping(target = "type", source = "direction", qualifiedByName = "adjustmentType")
    @Mapping(target = "supplier", ignore = true)
    @Mapping(target = "project", ignore = true)
    @Mapping(target = "reservation", ignore = true)
    @Mapping(target = "relatedMovement", ignore = true)
    StockMovement toAdjustmentEntity(StockMovementAdjustmentRequest request);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "material", source = "materialId")
    @Mapping(target = "warehouse", source = "sourceWarehouseId")
    @Mapping(target = "type", constant = "OUTGOING_TRANSFER")
    @Mapping(target = "supplier", ignore = true)
    @Mapping(target = "project", ignore = true)
    @Mapping(target = "reservation", ignore = true)
    @Mapping(target = "relatedMovement", ignore = true)
    StockMovement toOutgoingTransferEntity(StockMovementTransferRequest request);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "material", source = "materialId")
    @Mapping(target = "warehouse", source = "targetWarehouseId")
    @Mapping(target = "type", constant = "INCOMING_TRANSFER")
    @Mapping(target = "supplier", ignore = true)
    @Mapping(target = "project", ignore = true)
    @Mapping(target = "reservation", ignore = true)
    @Mapping(target = "relatedMovement", ignore = true)
    StockMovement toIncomingTransferEntity(StockMovementTransferRequest request);

    @Named("adjustmentType")
    default StockMovementType toAdjustmentType(StockAdjustmentDirection direction) {
        if (direction == null) {
            return null;
        }
        return StockAdjustmentDirection.POSITIVE == direction
                ? StockMovementType.POSITIVE_ADJUSTMENT
                : StockMovementType.NEGATIVE_ADJUSTMENT;
    }

    default PageResponse<StockMovementResponse> toPageResponse(Page<StockMovement> page) {
        return PageMapper.toPageResponse(page, this::toResponse);
    }
}