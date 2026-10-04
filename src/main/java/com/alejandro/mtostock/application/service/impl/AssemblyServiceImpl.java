package com.alejandro.mtostock.application.service.impl;

import com.alejandro.mtostock.application.dto.assembly.AssemblyAvailabilityResponse;
import com.alejandro.mtostock.application.dto.assembly.AssemblyComponentRequest;
import com.alejandro.mtostock.application.dto.assembly.AssemblyRequest;
import com.alejandro.mtostock.application.dto.assembly.AssemblyResponse;
import com.alejandro.mtostock.application.dto.assembly.AssemblyUpdateRequest;
import com.alejandro.mtostock.application.dto.audit.EntityRevisionResponse;
import com.alejandro.mtostock.application.dto.common.PageResponse;
import com.alejandro.mtostock.application.exception.NotFoundException;
import com.alejandro.mtostock.application.mapper.AssemblyMapper;
import com.alejandro.mtostock.application.service.AssemblyService;
import com.alejandro.mtostock.application.service.BOMCalculationService;
import com.alejandro.mtostock.application.service.EntityAuditService;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

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
    private final EntityAuditService entityAuditService;
    private final InventoryValidationService inventoryValidationService;
    private final BOMCalculationService bomCalculationService;
    private final CacheInvalidator cacheInvalidator;

    @Override
    @Transactional
    public AssemblyResponse create(AssemblyRequest request) {
        inventoryValidationService.validateAssemblyCodeIsUnique(request.code(), null);
        inventoryValidationService.validateAssemblyComponentsAreDistinct(materialIds(request.components()));
        Assembly assembly = assemblyMapper.toEntity(request);
        attachManagedComponentMaterials(assembly);
        inventoryValidationService.validateAssemblyHasComponents(assembly);
        Assembly savedAssembly = assemblyRepository.save(assembly);
        log.info("Assembly created with code {}", savedAssembly.getCode());
        return assemblyMapper.toResponse(savedAssembly);
    }

    /**
     * La lista de materiales que llega es la nueva lista entera, y sustituye a la anterior emparejando
     * por material (ver {@link #replaceComponents}).
     */
    @Override
    @Transactional
    public AssemblyResponse update(UUID id, AssemblyUpdateRequest request) {
        Assembly assembly = assemblyRepository.findWithComponentsById(id).orElseThrow(() -> new NotFoundException("Assembly", id));
        inventoryValidationService.validateAssemblyCodeIsUnique(request.code(), id);
        List<AssemblyComponentRequest> components = request.components() == null ? List.of() : request.components();
        inventoryValidationService.validateAssemblyComponentsAreDistinct(materialIds(components));
        assemblyMapper.updateEntity(request, assembly);
        replaceComponents(assembly, components);
        attachManagedComponentMaterials(assembly);
        inventoryValidationService.validateAssemblyHasComponents(assembly);
        // Sin el flush, las líneas nuevas saldrían en la respuesta sin id ni auditoría, y una
        // restricción rota saltaría al confirmar, ya fuera del servicio.
        assemblyRepository.flush();
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
    public PageResponse<AssemblyResponse> search(String search, String code, String name, Boolean active, Pageable pageable) {
        Specification<Assembly> specification = Specification.where(AssemblySpecification.codeOrNameContains(search))
                .and(AssemblySpecification.codeContains(code))
                .and(AssemblySpecification.nameContains(name))
                .and(AssemblySpecification.activeEquals(active));
        return assemblyMapper.toPageResponse(assemblyRepository.findAll(specification, pageable));
    }

    @Override
    @Transactional(readOnly = true)
    public AssemblyAvailabilityResponse calculateAvailability(UUID assemblyId, UUID warehouseId) {
        return bomCalculationService.calculateAvailability(assemblyId, warehouseId);
    }

    /**
     * Deja en el conjunto las líneas pedidas, emparejadas por material con las que ya tiene: la de un
     * material que sigue conserva su id y solo cambia su cantidad si cambió (2 y 2.000000 son la
     * misma), la de uno que ya no está se quita ({@code orphanRemoval} la borra) y la de uno nuevo se
     * añade. Nunca se borra y se vuelve a insertar la línea de un mismo material, así que la
     * restricción única no ve dos filas a la vez, y Envers apunta una modificación de la línea.
     */
    private void replaceComponents(Assembly assembly, List<AssemblyComponentRequest> requested) {
        Map<UUID, AssemblyComponentRequest> pending = requested.stream()
                .collect(Collectors.toMap(AssemblyComponentRequest::materialId, Function.identity(), (first, second) -> second,
                        LinkedHashMap::new));
        for (AssemblyComponent component : new ArrayList<>(assembly.getComponents())) {
            AssemblyComponentRequest line = pending.remove(component.getMaterial().getId());
            if (line == null) {
                assembly.removeComponent(component);
            } else if (component.getQuantity().compareTo(line.quantity()) != 0) {
                component.setQuantity(line.quantity());
            }
        }
        pending.values().forEach(line -> assembly.addComponent(assemblyMapper.toComponentEntity(line)));
    }

    private static List<UUID> materialIds(List<AssemblyComponentRequest> components) {
        return components == null ? List.of() : components.stream().map(AssemblyComponentRequest::materialId).toList();
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

    @Override
    @Transactional(readOnly = true)
    public PageResponse<EntityRevisionResponse<AssemblyResponse>> findRevisions(UUID id, Pageable pageable) {
        return entityAuditService.findRevisions(
                Assembly.class,
                id,
                assemblyMapper::toResponse,
                pageable);
    }
}
