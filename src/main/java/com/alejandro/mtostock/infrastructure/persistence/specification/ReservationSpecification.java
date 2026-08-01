package com.alejandro.mtostock.infrastructure.persistence.specification;

import com.alejandro.mtostock.infrastructure.persistence.entity.Reservation;
import com.alejandro.mtostock.infrastructure.persistence.entity.ReservationStatus;
import org.springframework.data.jpa.domain.Specification;

import java.util.UUID;

/**
 * Composable Specifications for reservation searches by related aggregate and status.
 */
public final class ReservationSpecification {

    private ReservationSpecification() {
    }

    public static Specification<Reservation> warehouseIdEquals(UUID warehouseId) {
        return SpecificationUtils.associationIdEquals("warehouse", warehouseId);
    }

    public static Specification<Reservation> statusEquals(ReservationStatus status) {
        return SpecificationUtils.equalsEnum("status", status);
    }

    public static Specification<Reservation> projectIdEquals(UUID projectId) {
        return SpecificationUtils.associationIdEquals("project", projectId);
    }

    public static Specification<Reservation> materialIdEquals(UUID materialId) {
        return SpecificationUtils.associationIdEquals("material", materialId);
    }
}