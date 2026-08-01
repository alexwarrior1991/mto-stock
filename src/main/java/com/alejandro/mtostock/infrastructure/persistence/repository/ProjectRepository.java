package com.alejandro.mtostock.infrastructure.persistence.repository;

import com.alejandro.mtostock.infrastructure.persistence.entity.Project;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Thin Spring Data repository for project persistence.
 */
public interface ProjectRepository extends JpaRepository<Project, UUID> {

    Optional<Project> findByCode(String code);
    
    boolean existsByCode(String code);
}