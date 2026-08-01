package com.alejandro.mtostock.infrastructure.persistence.repository;

import com.alejandro.mtostock.infrastructure.persistence.entity.Assembly;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

/**
 * Thin Spring Data repository for assembly aggregate persistence and specification-based search.
 */
public interface AssemblyRepository extends JpaRepository<Assembly, UUID>, JpaSpecificationExecutor<Assembly> {

    Optional<Assembly> findByCode(String code);

    boolean existsByCode(String code);

    
    /**
     * Loads the bill of materials in one query when callers need full assembly details.
     */
    @EntityGraph(attributePaths = {"components", "components.material"})
    Optional<Assembly> findWithComponentsById(UUID id);
}