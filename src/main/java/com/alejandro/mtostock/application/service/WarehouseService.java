package com.alejandro.mtostock.application.service;

import com.alejandro.mtostock.application.dto.common.PageResponse;
import com.alejandro.mtostock.application.dto.material.MaterialStockResponse;
import com.alejandro.mtostock.application.dto.stock.StockMovementResponse;
import com.alejandro.mtostock.application.dto.stock.StockMovementTransferRequest;
import com.alejandro.mtostock.application.dto.warehouse.WarehouseRequest;
import com.alejandro.mtostock.application.dto.warehouse.WarehouseResponse;
import com.alejandro.mtostock.application.dto.warehouse.WarehouseUpdateRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

/**
 * Application service exposing warehouse use cases and stock views.
 */
public interface WarehouseService {

    WarehouseResponse create(WarehouseRequest request);

    WarehouseResponse update(UUID id, WarehouseUpdateRequest request);

    WarehouseResponse findById(UUID id);

    PageResponse<WarehouseResponse> findAll(Pageable pageable);

    MaterialStockResponse calculateMaterialStock(UUID warehouseId, UUID materialId);

    List<StockMovementResponse> transfer(StockMovementTransferRequest request);
}