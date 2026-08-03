package com.alejandro.mtostock.application.service;

import com.alejandro.mtostock.application.dto.assembly.AssemblyAvailabilityResponse;

import java.util.UUID;

/**
 * Domain service responsible only for bill-of-materials availability calculations.
 */
public interface BOMCalculationService {

    AssemblyAvailabilityResponse calculateAvailability(UUID assemblyId, UUID warehouseId);

    void validateComponentAvailability(UUID assemblyId, UUID warehouseId);
}