package com.alejandro.mtostock.infrastructure.persistence.repository;

import com.alejandro.mtostock.infrastructure.persistence.entity.Supplier;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Thin Spring Data repository for supplier persistence.
 */
public interface SupplierRepository extends JpaRepository<Supplier, UUID> {

    Optional<Supplier> findByCode(String code);
    
    boolean existsByCode(String code);
}