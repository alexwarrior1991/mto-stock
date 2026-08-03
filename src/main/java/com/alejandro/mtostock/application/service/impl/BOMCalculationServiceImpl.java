package com.alejandro.mtostock.application.service.impl;

import com.alejandro.mtostock.application.dto.assembly.AssemblyAvailabilityComponentResponse;
import com.alejandro.mtostock.application.dto.assembly.AssemblyAvailabilityResponse;
import com.alejandro.mtostock.application.exception.AssemblyException;
import com.alejandro.mtostock.application.exception.NotFoundException;
import com.alejandro.mtostock.application.mapper.AssemblyMapper;
import com.alejandro.mtostock.application.mapper.MaterialMapper;
import com.alejandro.mtostock.application.mapper.WarehouseMapper;
import com.alejandro.mtostock.application.service.BOMCalculationService;
import com.alejandro.mtostock.application.service.InventoryValidationService;
import com.alejandro.mtostock.application.service.StockCalculationService;
import com.alejandro.mtostock.infrastructure.persistence.entity.Assembly;
import com.alejandro.mtostock.infrastructure.persistence.entity.AssemblyComponent;
import com.alejandro.mtostock.infrastructure.persistence.entity.Warehouse;
import com.alejandro.mtostock.infrastructure.persistence.repository.AssemblyRepository;
import com.alejandro.mtostock.infrastructure.persistence.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Calculates virtual assembly availability from BOM component requirements and material stock.
 */
@Service
@ConditionalOnBean(AssemblyRepository.class)
@RequiredArgsConstructor
class BOMCalculationServiceImpl implements BOMCalculationService {

    private static final Logger log = LoggerFactory.getLogger(BOMCalculationServiceImpl.class);

    private final AssemblyRepository assemblyRepository;
    private final WarehouseRepository warehouseRepository;
    private final StockCalculationService stockCalculationService;
    private final InventoryValidationService inventoryValidationService;
    private final AssemblyMapper assemblyMapper;
    private final MaterialMapper materialMapper;
    private final WarehouseMapper warehouseMapper;

    @Override
    @Transactional(readOnly = true)
    public AssemblyAvailabilityResponse calculateAvailability(UUID assemblyId, UUID warehouseId) {
        Assembly assembly = assemblyRepository.findWithComponentsById(assemblyId)
                .orElseThrow(() -> new NotFoundException("Assembly", assemblyId));
        Warehouse warehouse = warehouseRepository.findById(warehouseId)
                .orElseThrow(() -> new NotFoundException("Warehouse", warehouseId));
        inventoryValidationService.validateActive(assembly);
        inventoryValidationService.validateActive(warehouse);
        inventoryValidationService.validateAssemblyHasComponents(assembly);

        List<AssemblyAvailabilityComponentResponse> componentResponses = assembly.getComponents().stream()
                .map(component -> toAvailabilityComponent(component, warehouseId))
                .toList();
        BigDecimal assemblyAvailability = componentResponses.stream()
                .map(AssemblyAvailabilityComponentResponse::producibleAssemblyQuantity)
                .min(Comparator.naturalOrder())
                .orElse(BigDecimal.ZERO);
        List<AssemblyAvailabilityComponentResponse> markedComponents = componentResponses.stream()
                .map(component -> markLimitingComponent(component, assemblyAvailability))
                .toList();

        log.info("Assembly availability calculated for assembly {} in warehouse {}", assembly.getCode(), warehouse.getCode());
        return new AssemblyAvailabilityResponse(
                assemblyMapper.toSummaryResponse(assembly),
                warehouseMapper.toSummaryResponse(warehouse),
                assemblyAvailability,
                markedComponents,
                Instant.now()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public void validateComponentAvailability(UUID assemblyId, UUID warehouseId) {
        AssemblyAvailabilityResponse availability = calculateAvailability(assemblyId, warehouseId);
        if (availability.availableQuantity().signum() <= 0) {
            throw new AssemblyException("Assembly '%s' cannot be produced with the current component availability"
                    .formatted(availability.assembly().code()));
        }
    }

    private AssemblyAvailabilityComponentResponse toAvailabilityComponent(AssemblyComponent component, UUID warehouseId) {
        BigDecimal physical = stockCalculationService.calculatePhysicalStock(component.getMaterial().getId(), warehouseId);
        BigDecimal reserved = stockCalculationService.calculateReservedStock(component.getMaterial().getId(), warehouseId);
        BigDecimal available = physical.subtract(reserved);
        BigDecimal producible = available.divideToIntegralValue(component.getQuantity());
        return new AssemblyAvailabilityComponentResponse(
                materialMapper.toSummaryResponse(component.getMaterial()),
                component.getQuantity(),
                physical,
                reserved,
                available,
                producible,
                false
        );
    }

    private AssemblyAvailabilityComponentResponse markLimitingComponent(
            AssemblyAvailabilityComponentResponse component,
            BigDecimal assemblyAvailability
    ) {
        return new AssemblyAvailabilityComponentResponse(
                component.material(),
                component.requiredQuantityPerAssembly(),
                component.onHandQuantity(),
                component.activeReservedQuantity(),
                component.availableQuantity(),
                component.producibleAssemblyQuantity(),
                component.producibleAssemblyQuantity().compareTo(assemblyAvailability) == 0
        );
    }
}