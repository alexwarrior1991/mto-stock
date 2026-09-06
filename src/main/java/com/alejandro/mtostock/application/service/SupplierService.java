package com.alejandro.mtostock.application.service;

import com.alejandro.mtostock.application.dto.audit.EntityRevisionResponse;
import com.alejandro.mtostock.application.dto.common.PageResponse;
import com.alejandro.mtostock.application.dto.supplier.SupplierRequest;
import com.alejandro.mtostock.application.dto.supplier.SupplierResponse;
import com.alejandro.mtostock.application.dto.supplier.SupplierUpdateRequest;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * Application service exposing supplier catalogue use cases.
 */
public interface SupplierService {

    SupplierResponse create(SupplierRequest request);

    SupplierResponse update(UUID id, SupplierUpdateRequest request);

    SupplierResponse findById(UUID id);

    PageResponse<SupplierResponse> findAll(Pageable pageable);

    /**
     * Historial de cambios del proveedor.
     *
     * @throws com.alejandro.mtostock.application.exception.NotFoundException si no existe
     */
    PageResponse<EntityRevisionResponse<SupplierResponse>> findRevisions(UUID id, Pageable pageable);
}
