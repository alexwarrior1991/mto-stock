package com.alejandro.mtostock.application.service.impl;

import com.alejandro.mtostock.application.dto.common.PageResponse;
import com.alejandro.mtostock.application.dto.supplier.SupplierRequest;
import com.alejandro.mtostock.application.dto.supplier.SupplierResponse;
import com.alejandro.mtostock.application.dto.supplier.SupplierUpdateRequest;
import com.alejandro.mtostock.application.exception.NotFoundException;
import com.alejandro.mtostock.application.mapper.SupplierMapper;
import com.alejandro.mtostock.application.service.InventoryValidationService;
import com.alejandro.mtostock.application.service.SupplierService;
import com.alejandro.mtostock.configuration.cache.CacheInvalidator;
import com.alejandro.mtostock.configuration.cache.CacheNames;
import com.alejandro.mtostock.infrastructure.persistence.entity.Supplier;
import com.alejandro.mtostock.infrastructure.persistence.repository.SupplierRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Orchestrates supplier catalogue use cases without embedding inventory rules.
 */
@Service
@RequiredArgsConstructor
class SupplierServiceImpl implements SupplierService {

    private static final Logger log = LoggerFactory.getLogger(SupplierServiceImpl.class);

    private final SupplierRepository supplierRepository;
    private final SupplierMapper supplierMapper;
    private final InventoryValidationService inventoryValidationService;
    private final CacheInvalidator cacheInvalidator;

    @Override
    @Transactional
    public SupplierResponse create(SupplierRequest request) {
        inventoryValidationService.validateSupplierCodeIsUnique(request.code(), null);
        Supplier supplier = supplierRepository.save(supplierMapper.toEntity(request));
        log.info("Supplier created with code {}", supplier.getCode());
        return supplierMapper.toResponse(supplier);
    }

    @Override
    @Transactional
    public SupplierResponse update(UUID id, SupplierUpdateRequest request) {
        Supplier supplier = supplierRepository.findById(id).orElseThrow(() -> new NotFoundException("Supplier", id));
        inventoryValidationService.validateSupplierCodeIsUnique(request.code(), id);
        supplierMapper.updateEntity(request, supplier);
        log.info("Supplier {} updated", supplier.getCode());
        cacheInvalidator.evictAfterCommit(CacheNames.SUPPLIERS, id);
        return supplierMapper.toResponse(supplier);
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CacheNames.SUPPLIERS, key = "#id")
    public SupplierResponse findById(UUID id) {
        return supplierMapper.toResponse(supplierRepository.findById(id).orElseThrow(() -> new NotFoundException("Supplier", id)));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<SupplierResponse> findAll(Pageable pageable) {
        return supplierMapper.toPageResponse(supplierRepository.findAll(pageable));
    }
}