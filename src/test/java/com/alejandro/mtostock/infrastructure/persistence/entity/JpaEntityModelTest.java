package com.alejandro.mtostock.infrastructure.persistence.entity;

import jakarta.persistence.Entity;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JpaEntityModelTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void materialValidationRejectsBlankCodeAndNegativeMinimumStock() {
        Material material = Material.builder()
                .code(" ")
                .name("Contact wire")
                .unitOfMeasure("m")
                .minimumStockLevel(new BigDecimal("-1.000000"))
                .build();

        var violations = validator.validate(material);

        assertTrue(violations.stream().anyMatch(violation -> "code".contentEquals(violation.getPropertyPath().toString())));
        assertTrue(violations.stream().anyMatch(violation -> "minimumStockLevel".contentEquals(violation.getPropertyPath().toString())));
    }

    @Test
    void assemblyMaintainsBidirectionalBomRelationship() {
        Assembly assembly = Assembly.builder()
                .code("ASM-001")
                .name("Basic catenary section")
                .build();
        AssemblyComponent component = AssemblyComponent.builder()
                .material(material())
                .quantity(new BigDecimal("2.000000"))
                .build();

        assembly.addComponent(component);

        assertEquals(1, assembly.getComponents().size());
        assertEquals(assembly, component.getAssembly());

        assembly.removeComponent(component);

        assertTrue(assembly.getComponents().isEmpty());
        assertNull(component.getAssembly());
    }

    @Test
    void reservationReleaseAndCancellationRequireActiveReservation() {
        Reservation reservation = reservation();
        Instant releasedAt = Instant.parse("2026-08-01T10:08:00Z");

        reservation.release(releasedAt);

        assertEquals(ReservationStatus.RELEASED, reservation.getStatus());
        assertEquals(releasedAt, reservation.getReleasedAt());
        assertFalse(reservation.isActive());
        assertThrows(IllegalStateException.class, () -> reservation.cancel(releasedAt));
    }

    @Test
    void stockMovementCalculatesSignedQuantityAndRejectsSelfRelation() {
        StockMovement entry = stockMovement(StockMovementType.ENTRY, "10.500000");
        StockMovement output = stockMovement(StockMovementType.OUTPUT, "4.250000");

        assertEquals(new BigDecimal("10.500000"), entry.signedQuantity());
        assertEquals(new BigDecimal("-4.250000"), output.signedQuantity());
        assertThrows(IllegalArgumentException.class, () -> entry.relateTo(entry));
    }

    private static StockMovement stockMovement(StockMovementType type, String quantity) {
        return StockMovement.builder()
                .material(material())
                .warehouse(warehouse())
                .type(type)
                .quantity(new BigDecimal(quantity))
                .build();
    }

    private static Reservation reservation() {
        return Reservation.builder()
                .material(material())
                .warehouse(warehouse())
                .project(project())
                .quantity(new BigDecimal("5.000000"))
                .build();
    }

    private static Material material() {
        return Material.builder()
                .code("MAT-001")
                .name("Contact wire")
                .unitOfMeasure("m")
                .build();
    }

    private static Warehouse warehouse() {
        return Warehouse.builder()
                .code("WH-001")
                .name("Main warehouse")
                .build();
    }

    private static Project project() {
        return Project.builder()
                .code("PRJ-001")
                .name("Railway project")
                .build();
    }


    /**
     * El reparto entre lo que tiene historial y lo que no, fijado donde se puede romper sin que
     * nadie se entere: en las anotaciones.
     *
     * <p>La forma cómoda de anotar esto sería poner {@code @Audited} en {@code AuditableEntity}, y
     * entonces {@code stock_movement} —un libro mayor que ya es inmutable— duplicaría la tabla más
     * grande del sistema, e {@code inventory_balance} e {@code inbox_message} tendrían gemelas
     * permanentemente vacías, porque se escriben con SQL nativo y Envers no ve esas escrituras. Una
     * gemela vacía no se lee como «aquí no hay auditoría», se lee como «esto no ha cambiado nunca».
     * Además Envers pediría tres tablas que {@code V7} no crea y, con {@code ddl-auto: validate}, la
     * aplicación no arrancaría — en el entorno que ejecute la migración primero, no aquí.</p>
     */
    @Test
    void auditedEntitiesAreExactlyTheMasterDataAndTheReservation() {
        List<Class<?>> entities = List.of(
                Material.class, Supplier.class, Warehouse.class, Project.class, Assembly.class,
                AssemblyComponent.class, Reservation.class,
                StockMovement.class, InventoryBalance.class, InboxMessage.class);

        entities.forEach(entity -> assertTrue(entity.isAnnotationPresent(Entity.class),
                entity.getSimpleName() + " should be a JPA entity"));

        Set<Class<?>> audited = entities.stream()
                .filter(entity -> entity.isAnnotationPresent(Audited.class))
                .collect(java.util.stream.Collectors.toSet());

        assertEquals(Set.of(Material.class, Supplier.class, Warehouse.class, Project.class,
                Assembly.class, AssemblyComponent.class, Reservation.class), audited);

        Stream.of(StockMovement.class, InventoryBalance.class, InboxMessage.class)
                .forEach(entity -> assertFalse(entity.isAnnotationPresent(Audited.class),
                        entity.getSimpleName() + " must not be audited: see AuditableEntity"));
    }

    /**
     * {@code audit_revision} ya guarda quién y cuándo una vez por revisión, así que las gemelas
     * {@code _aud} no repiten estas cuatro columnas. La anotación es explícita y no confianza en el
     * valor por defecto: lo que hace Envers con las propiedades de un {@code @MappedSuperclass} sin
     * {@code @Audited} ha cambiado entre versiones, y dejarlo al criterio de la versión convierte una
     * subida de Hibernate en un fallo de arranque por una columna que sobra o falta en la gemela.
     */
    @Test
    void auditMetadataColumnsAreNotCopiedIntoTheHistoryTables() {
        Stream.of("createdAt", "updatedAt", "createdBy", "updatedBy")
                .map(JpaEntityModelTest::auditableField)
                .forEach(field -> assertTrue(field.isAnnotationPresent(NotAudited.class),
                        field.getName() + " must be @NotAudited"));

        // El identificador es la mitad de la clave primaria de la tabla de historial: Envers lo mapea
        // siempre, y marcarlo aqui no tendria sentido.
        assertFalse(auditableField("id").isAnnotationPresent(NotAudited.class));
    }

    private static Field auditableField(String name) {
        try {
            return AuditableEntity.class.getDeclaredField(name);
        } catch (NoSuchFieldException cause) {
            throw new AssertionError("AuditableEntity should declare " + name, cause);
        }
    }
}
