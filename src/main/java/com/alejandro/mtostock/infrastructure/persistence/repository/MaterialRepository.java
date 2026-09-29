package com.alejandro.mtostock.infrastructure.persistence.repository;

import com.alejandro.mtostock.infrastructure.persistence.entity.Material;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.Optional;
import java.util.UUID;

/**
 * Thin Spring Data repository for material aggregate persistence and specification-based search.
 */
public interface MaterialRepository extends JpaRepository<Material, UUID>, JpaSpecificationExecutor<Material> {

    Optional<Material> findByCode(String code);

    boolean existsByCode(String code);

    /**
     * La fila del material bloqueada ({@code select ... for update}) hasta el final de la transaccion.
     * Es lo que serializa las escrituras del saldo de un mismo material: el orden de bloqueo es
     * siempre material y despues {@code inventory_balance}, para que el cruce por debajo del minimo
     * lo vea exactamente una operacion (ver {@code InventoryBalanceServiceImpl}).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Material m where m.id = :id")
    Optional<Material> findByIdForUpdate(@Param("id") UUID id);
}