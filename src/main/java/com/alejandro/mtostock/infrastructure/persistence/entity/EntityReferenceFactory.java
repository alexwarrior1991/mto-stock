package com.alejandro.mtostock.infrastructure.persistence.entity;

import org.mapstruct.ObjectFactory;
import org.mapstruct.TargetType;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Creates lightweight entity references from identifiers for mapper-generated relationship assignments.
 */
@Component
public class EntityReferenceFactory {

    @ObjectFactory
    public Material newMaterial(@TargetType Class<Material> type) {
        return new Material();
    }

    @ObjectFactory
    public Warehouse newWarehouse(@TargetType Class<Warehouse> type) {
        return new Warehouse();
    }

    @ObjectFactory
    public Supplier newSupplier(@TargetType Class<Supplier> type) {
        return new Supplier();
    }

    @ObjectFactory
    public Project newProject(@TargetType Class<Project> type) {
        return new Project();
    }

    @ObjectFactory
    public Assembly newAssembly(@TargetType Class<Assembly> type) {
        return new Assembly();
    }

    @ObjectFactory
    public AssemblyComponent newAssemblyComponent(@TargetType Class<AssemblyComponent> type) {
        return new AssemblyComponent();
    }

    @ObjectFactory
    public Reservation newReservation(@TargetType Class<Reservation> type) {
        return new Reservation();
    }

    @ObjectFactory
    public StockMovement newStockMovement(@TargetType Class<StockMovement> type) {
        return new StockMovement();
    }

    public Material toMaterial(UUID id) {
        if (id == null) {
            return null;
        }
        Material material = new Material();
        material.setId(id);
        return material;
    }

    public Warehouse toWarehouse(UUID id) {
        if (id == null) {
            return null;
        }
        Warehouse warehouse = new Warehouse();
        warehouse.setId(id);
        return warehouse;
    }

    public Supplier toSupplier(UUID id) {
        if (id == null) {
            return null;
        }
        Supplier supplier = new Supplier();
        supplier.setId(id);
        return supplier;
    }

    public Project toProject(UUID id) {
        if (id == null) {
            return null;
        }
        Project project = new Project();
        project.setId(id);
        return project;
    }

    public Reservation toReservation(UUID id) {
        if (id == null) {
            return null;
        }
        Reservation reservation = new Reservation();
        reservation.setId(id);
        return reservation;
    }

    public StockMovement toStockMovement(UUID id) {
        if (id == null) {
            return null;
        }
        StockMovement movement = new StockMovement();
        movement.setId(id);
        return movement;
    }
}