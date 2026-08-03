package com.alejandro.mtostock.application.service;

import com.alejandro.mtostock.application.dto.assembly.AssemblyAvailabilityResponse;
import com.alejandro.mtostock.application.dto.assembly.AssemblyRequest;
import com.alejandro.mtostock.application.dto.assembly.AssemblyResponse;
import com.alejandro.mtostock.application.dto.assembly.AssemblyUpdateRequest;
import com.alejandro.mtostock.application.dto.common.PageResponse;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * Application service exposing virtual assembly and bill-of-materials use cases.
 */
public interface AssemblyService {

    AssemblyResponse create(AssemblyRequest request);

    AssemblyResponse update(UUID id, AssemblyUpdateRequest request);

    AssemblyResponse findById(UUID id);

    PageResponse<AssemblyResponse> search(String code, String name, Boolean active, Pageable pageable);

    AssemblyAvailabilityResponse calculateAvailability(UUID assemblyId, UUID warehouseId);
}