package com.alejandro.mtostock.application.mapper;

import com.alejandro.mtostock.application.dto.assembly.AssemblyComponentRequest;
import com.alejandro.mtostock.application.dto.assembly.AssemblyRequest;
import com.alejandro.mtostock.application.dto.material.MaterialUpdateRequest;
import com.alejandro.mtostock.application.dto.reservation.ReservationStatusDto;
import com.alejandro.mtostock.application.dto.reservation.ReservationStatusUpdateRequest;
import com.alejandro.mtostock.application.dto.stock.StockAdjustmentDirection;
import com.alejandro.mtostock.application.dto.stock.StockMovementAdjustmentRequest;
import com.alejandro.mtostock.application.dto.stock.StockMovementTransferRequest;
import com.alejandro.mtostock.infrastructure.persistence.entity.Assembly;
import com.alejandro.mtostock.infrastructure.persistence.entity.AssemblyComponent;
import com.alejandro.mtostock.infrastructure.persistence.entity.EntityReferenceFactory;
import com.alejandro.mtostock.infrastructure.persistence.entity.Material;
import com.alejandro.mtostock.infrastructure.persistence.entity.Reservation;
import com.alejandro.mtostock.infrastructure.persistence.entity.ReservationStatus;
import com.alejandro.mtostock.infrastructure.persistence.entity.StockMovementType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
        "spring.data.jpa.auditing.enabled=false"
})
class MapperLayerTest {

    @Autowired
    private MaterialMapper materialMapper;

    @Autowired
    private AssemblyMapper assemblyMapper;

    @Autowired
    private ReservationMapper reservationMapper;

    @Autowired
    private StockMovementMapper stockMovementMapper;

    private final EntityReferenceFactory references = new EntityReferenceFactory();

    @Test
    void mapsAssemblyEntityToResponseWithNestedComponentsAndAuditMetadata() {
        UUID materialId = UUID.randomUUID();
        Material material = references.toMaterial(materialId);
        material.setCode("MAT-001");
        material.setName("Contact wire");
        material.setUnitOfMeasure("m");
        material.setActive(true);

        Assembly assembly = Assembly.builder()
                .code("ASM-001")
                .name("Basic catenary section")
                .active(true)
                .build();
        assembly.setCreatedAt(Instant.parse("2026-08-01T09:00:00Z"));
        assembly.setUpdatedAt(Instant.parse("2026-08-01T10:00:00Z"));
        assembly.setCreatedBy("architect");
        assembly.setUpdatedBy("mapper-test");

        AssemblyComponent component = AssemblyComponent.builder()
                .material(material)
                .quantity(new BigDecimal("2.000000"))
                .build();
        component.setCreatedAt(Instant.parse("2026-08-01T09:05:00Z"));
        component.setUpdatedAt(Instant.parse("2026-08-01T10:05:00Z"));
        component.setCreatedBy("architect");
        component.setUpdatedBy("mapper-test");
        assembly.addComponent(component);

        var response = assemblyMapper.toResponse(assembly);

        assertEquals("ASM-001", response.code());
        assertEquals("architect", response.audit().createdBy());
        assertEquals(1, response.components().size());
        assertEquals(materialId, response.components().getFirst().material().id());
        assertEquals(new BigDecimal("2.000000"), response.components().getFirst().quantity());
        assertEquals("mapper-test", response.components().getFirst().audit().updatedBy());
    }

    @Test
    void mapsAssemblyRequestToEntityAndLinksBomComponents() {
        UUID materialId = UUID.randomUUID();
        AssemblyRequest request = new AssemblyRequest(
                "ASM-002",
                "Messenger support",
                List.of(new AssemblyComponentRequest(materialId, new BigDecimal("3.000000")))
        );

        Assembly assembly = assemblyMapper.toEntity(request);

        assertEquals("ASM-002", assembly.getCode());
        assertTrue(assembly.getActive());
        assertEquals(1, assembly.getComponents().size());
        assertSame(assembly, assembly.getComponents().getFirst().getAssembly());
        assertEquals(materialId, assembly.getComponents().getFirst().getMaterial().getId());
    }

    @Test
    void updatesMaterialWithoutReplacingIdentityOrAuditMetadata() {
        UUID materialId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-08-01T08:00:00Z");
        Material material = references.toMaterial(materialId);
        material.setCode("OLD");
        material.setName("Old material");
        material.setUnitOfMeasure("m");
        material.setMinimumStockLevel(BigDecimal.ZERO);
        material.setActive(true);
        material.setCreatedAt(createdAt);
        material.setCreatedBy("architect");

        materialMapper.updateEntity(new MaterialUpdateRequest(
                "NEW",
                "Updated material",
                "kg",
                new BigDecimal("4.500000"),
                false
        ), material);

        assertEquals(materialId, material.getId());
        assertEquals(createdAt, material.getCreatedAt());
        assertEquals("architect", material.getCreatedBy());
        assertEquals("NEW", material.getCode());
        assertEquals(new BigDecimal("4.500000"), material.getMinimumStockLevel());
        assertEquals(false, material.getActive());
    }

    @Test
    void mapsReservationStatusAndStockMovementCommands() {
        Reservation reservation = Reservation.builder()
                .material(references.toMaterial(UUID.randomUUID()))
                .warehouse(references.toWarehouse(UUID.randomUUID()))
                .project(references.toProject(UUID.randomUUID()))
                .quantity(new BigDecimal("5.000000"))
                .build();

        reservationMapper.updateStatus(
                new ReservationStatusUpdateRequest(ReservationStatusDto.RELEASED, Instant.parse("2026-08-01T11:00:00Z")),
                reservation
        );

        assertEquals(ReservationStatus.RELEASED, reservation.getStatus());
        assertEquals(Instant.parse("2026-08-01T11:00:00Z"), reservation.getReleasedAt());

        UUID materialId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        var adjustment = stockMovementMapper.toAdjustmentEntity(new StockMovementAdjustmentRequest(
                materialId,
                warehouseId,
                StockAdjustmentDirection.NEGATIVE,
                new BigDecimal("1.250000"),
                null,
                "ADJ-001",
                "inventory count"
        ));

        assertEquals(StockMovementType.NEGATIVE_ADJUSTMENT, adjustment.getType());
        assertEquals(materialId, adjustment.getMaterial().getId());
        assertEquals(warehouseId, adjustment.getWarehouse().getId());
        assertNull(adjustment.getSupplier());

        UUID sourceWarehouseId = UUID.randomUUID();
        UUID targetWarehouseId = UUID.randomUUID();
        var transfer = new StockMovementTransferRequest(
                materialId,
                sourceWarehouseId,
                targetWarehouseId,
                new BigDecimal("2.000000"),
                null,
                "TRF-001",
                "relocation"
        );

        assertEquals(StockMovementType.OUTGOING_TRANSFER, stockMovementMapper.toOutgoingTransferEntity(transfer).getType());
        assertEquals(sourceWarehouseId, stockMovementMapper.toOutgoingTransferEntity(transfer).getWarehouse().getId());
        assertEquals(StockMovementType.INCOMING_TRANSFER, stockMovementMapper.toIncomingTransferEntity(transfer).getType());
        assertEquals(targetWarehouseId, stockMovementMapper.toIncomingTransferEntity(transfer).getWarehouse().getId());
    }
}