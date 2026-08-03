package com.alejandro.mtostock.application.service.impl;

import com.alejandro.mtostock.application.dto.common.PageResponse;
import com.alejandro.mtostock.application.dto.material.MaterialRequest;
import com.alejandro.mtostock.application.dto.material.MaterialResponse;
import com.alejandro.mtostock.application.dto.material.MaterialStockResponse;
import com.alejandro.mtostock.application.dto.material.MaterialUpdateRequest;
import com.alejandro.mtostock.application.exception.NotFoundException;
import com.alejandro.mtostock.application.mapper.MaterialMapper;
import com.alejandro.mtostock.application.service.InventoryValidationService;
import com.alejandro.mtostock.application.service.MaterialService;
import com.alejandro.mtostock.application.service.StockCalculationService;
import com.alejandro.mtostock.infrastructure.persistence.entity.Material;
import com.alejandro.mtostock.infrastructure.persistence.repository.MaterialRepository;
import com.alejandro.mtostock.infrastructure.persistence.specification.MaterialSpecification;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Orchestrates material catalogue use cases and delegates stock rules to focused services.
 */
@Service
@ConditionalOnBean(MaterialRepository.class)
@RequiredArgsConstructor
class MaterialServiceImpl implements MaterialService {

    private static final Logger log = LoggerFactory.getLogger(MaterialServiceImpl.class);

    private final MaterialRepository materialRepository;
    private final MaterialMapper materialMapper;
    private final InventoryValidationService inventoryValidationService;
    private final StockCalculationService stockCalculationService;

    @Override
    @Transactional
    public MaterialResponse create(MaterialRequest request) {
        inventoryValidationService.validateMaterialCodeIsUnique(request.code(), null);
        Material material = materialRepository.save(materialMapper.toEntity(request));
        log.info("Material created with code {}", material.getCode());
        return materialMapper.toResponse(material);
    }

    @Override
    @Transactional
    public MaterialResponse update(UUID id, MaterialUpdateRequest request) {
        Material material = materialRepository.findById(id).orElseThrow(() -> new NotFoundException("Material", id));
        inventoryValidationService.validateMaterialCodeIsUnique(request.code(), id);
        materialMapper.updateEntity(request, material);
        log.info("Material {} updated", material.getCode());
        return materialMapper.toResponse(material);
    }

    @Override
    @Transactional(readOnly = true)
    public MaterialResponse findById(UUID id) {
        return materialMapper.toResponse(materialRepository.findById(id).orElseThrow(() -> new NotFoundException("Material", id)));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<MaterialResponse> search(String code, String name, Boolean active, UUID warehouseId, Boolean belowMinimum, Pageable pageable) {
        Specification<Material> specification = Specification.where(MaterialSpecification.codeContains(code))
                .and(MaterialSpecification.nameContains(name))
                .and(MaterialSpecification.activeEquals(active))
                .and(MaterialSpecification.storedInWarehouse(warehouseId));
        if (Boolean.TRUE.equals(belowMinimum)) {
            specification = specification.and(MaterialSpecification.stockBelowMinimum(warehouseId));
        }
        return materialMapper.toPageResponse(materialRepository.findAll(specification, pageable));
    }

    @Override
    @Transactional(readOnly = true)
    public MaterialStockResponse calculateStock(UUID materialId, UUID warehouseId) {
        return stockCalculationService.calculateMaterialStock(materialId, warehouseId);
    }
}