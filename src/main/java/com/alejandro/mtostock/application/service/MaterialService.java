package com.alejandro.mtostock.application.service;

import com.alejandro.mtostock.application.dto.audit.EntityRevisionResponse;
import com.alejandro.mtostock.application.dto.common.PageResponse;
import com.alejandro.mtostock.application.dto.material.MaterialRequest;
import com.alejandro.mtostock.application.dto.material.MaterialResponse;
import com.alejandro.mtostock.application.dto.material.MaterialStockResponse;
import com.alejandro.mtostock.application.dto.material.MaterialUpdateRequest;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * Application service exposing material catalogue use cases.
 */
public interface MaterialService {

    MaterialResponse create(MaterialRequest request);

    MaterialResponse update(UUID id, MaterialUpdateRequest request);

    MaterialResponse findById(UUID id);

    PageResponse<MaterialResponse> search(String code, String name, Boolean active, UUID warehouseId, Boolean belowMinimum, Pageable pageable);

    MaterialStockResponse calculateStock(UUID materialId, UUID warehouseId);

    /**
     * Historial de cambios del material: qué decía el catálogo en cada revisión y quién lo dejó así.
     *
     * @throws com.alejandro.mtostock.application.exception.NotFoundException si no existe
     */
    PageResponse<EntityRevisionResponse<MaterialResponse>> findRevisions(UUID id, Pageable pageable);
}
