package com.alejandro.mtostock.application.service.impl;

import com.alejandro.mtostock.application.dto.assembly.AssemblyAvailabilityResponse;
import com.alejandro.mtostock.application.dto.assembly.AssemblyRequest;
import com.alejandro.mtostock.application.dto.assembly.AssemblyResponse;
import com.alejandro.mtostock.application.dto.assembly.AssemblyUpdateRequest;
import com.alejandro.mtostock.application.dto.common.PageResponse;
import com.alejandro.mtostock.application.exception.NotFoundException;
import com.alejandro.mtostock.application.mapper.AssemblyMapper;
import com.alejandro.mtostock.application.service.AssemblyService;
import com.alejandro.mtostock.application.service.BOMCalculationService;
import com.alejandro.mtostock.application.service.InventoryValidationService;
import com.alejandro.mtostock.configuration.cache.CacheInvalidator;
import com.alejandro.mtostock.configuration.cache.CacheNames;
import com.alejandro.mtostock.infrastructure.persistence.entity.Assembly;
import com.alejandro.mtostock.infrastructure.persistence.entity.AssemblyComponent;
import com.alejandro.mtostock.infrastructure.persistence.entity.Material;
import com.alejandro.mtostock.infrastructure.persistence.repository.AssemblyRepository;
import com.alejandro.mtostock.infrastructure.persistence.repository.MaterialRepository;
import com.alejandro.mtostock.infrastructure.persistence.specification.AssemblySpecification;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Orchestrates assembly use cases and delegates BOM calculations to a dedicated domain service.
 */
@Service
@RequiredArgsConstructor
class AssemblyServiceImpl implements AssemblyService {

    private static final Logger log = LoggerFactory.getLogger(AssemblyServiceImpl.class);

    private final AssemblyRepository assemblyRepository;
    private final MaterialRepository materialRepository;
    private final AssemblyMapper assemblyMapper;
    private final InventoryValidationService inventoryValidationService;
    private final BOMCalculationService bomCalculationService;
    private final CacheInvalidator cacheInvalidator;

    @Override
    @Transactional
    public AssemblyResponse create(AssemblyRequest request) {
        inventoryValidationService.validateAssemblyCodeIsUnique(request.code(), null);
        Assembly assembly = assemblyMapper.toEntity(request);
        attachManagedComponentMaterials(assembly);
        inventoryValidationService.validateAssemblyHasComponents(assembly);
        Assembly savedAssembly = assemblyRepository.save(assembly);
        log.info("Assembly created with code {}", savedAssembly.getCode());
        return assemblyMapper.toResponse(savedAssembly);
    }

    @Override
    @Transactional
    public AssemblyResponse update(UUID id, AssemblyUpdateRequest request) {
        Assembly assembly = assemblyRepository.findWithComponentsById(id).orElseThrow(() -> new NotFoundException("Assembly", id));
        inventoryValidationService.validateAssemblyCodeIsUnique(request.code(), id);
        assemblyMapper.updateEntity(request, assembly);
        attachManagedComponentMaterials(assembly);
        inventoryValidationService.validateAssemblyHasComponents(assembly);
        log.info("Assembly {} updated", assembly.getCode());
        cacheInvalidator.evictAfterCommit(CacheNames.ASSEMBLIES, id);
        return assemblyMapper.toResponse(assembly);
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CacheNames.ASSEMBLIES, key = "#id")
    public AssemblyResponse findById(UUID id) {
        return assemblyMapper.toResponse(assemblyRepository.findWithComponentsById(id).orElseThrow(() -> new NotFoundException("Assembly", id)));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AssemblyResponse> search(String code, String name, Boolean active, Pageable pageable) {
        Specification<Assembly> specification = Specification.where(AssemblySpecification.codeContains(code))
                .and(AssemblySpecification.nameContains(name))
                .and(AssemblySpecification.activeEquals(active));
        return assemblyMapper.toPageResponse(assemblyRepository.findAll(specification, pageable));
    }

    @Override
    @Transactional(readOnly = true)
    public AssemblyAvailabilityResponse calculateAvailability(UUID assemblyId, UUID warehouseId) {
        return bomCalculationService.calculateAvailability(assemblyId, warehouseId);
    }

    private void attachManagedComponentMaterials(Assembly assembly) {
        for (AssemblyComponent component : assembly.getComponents()) {
            Material material = materialRepository.findById(component.getMaterial().getId())
                    .orElseThrow(() -> new NotFoundException("Material", component.getMaterial().getId()));
            inventoryValidationService.validateActive(material);
            component.setMaterial(material);
            component.setAssembly(assembly);
        }
    }
}