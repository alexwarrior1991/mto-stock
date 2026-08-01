package com.alejandro.mtostock.application.mapper;

import com.alejandro.mtostock.application.dto.common.AuditMetadataResponse;
import com.alejandro.mtostock.infrastructure.persistence.entity.AuditableEntity;
import org.mapstruct.Mapper;

/**
 * Maps audit metadata shared by persisted resources into response DTO fragments.
 */
@Mapper(config = MapStructCentralConfig.class)
public interface AuditableMapper {

    AuditMetadataResponse toAuditMetadata(AuditableEntity entity);
}