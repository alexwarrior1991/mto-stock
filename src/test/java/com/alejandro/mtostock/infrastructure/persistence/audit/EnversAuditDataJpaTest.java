package com.alejandro.mtostock.infrastructure.persistence.audit;

import com.alejandro.mtostock.infrastructure.persistence.entity.Assembly;
import com.alejandro.mtostock.infrastructure.persistence.entity.AssemblyComponent;
import com.alejandro.mtostock.infrastructure.persistence.entity.AuditableEntity;
import com.alejandro.mtostock.infrastructure.persistence.entity.Material;
import com.alejandro.mtostock.infrastructure.persistence.entity.Project;
import com.alejandro.mtostock.infrastructure.persistence.entity.Reservation;
import com.alejandro.mtostock.infrastructure.persistence.entity.ReservationStatus;
import com.alejandro.mtostock.infrastructure.persistence.entity.StockMovement;
import com.alejandro.mtostock.infrastructure.persistence.entity.StockMovementType;
import com.alejandro.mtostock.infrastructure.persistence.entity.Warehouse;
import com.alejandro.mtostock.support.PostgreSQLTestContainer;
import jakarta.persistence.EntityManager;
import org.hibernate.envers.AuditReader;
import org.hibernate.envers.AuditReaderFactory;
import org.hibernate.envers.RevisionType;
import org.hibernate.envers.query.AuditEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El historial de Envers, comprobado de punta a punta contra PostgreSQL real.
 *
 * <h2>Por qué esta clase apaga la transacción del test</h2>
 *
 * <p><b>Envers no escribe en el {@code flush()}: escribe justo antes de que la transacción
 * confirme.</b> Un {@code @DataJpaTest} normal envuelve cada método en una transacción que después
 * deshace, así que las filas de auditoría no llegarían a escribirse nunca y el test leería cero
 * revisiones — y la conclusión natural, equivocada, sería «Envers no funciona». De ahí
 * {@code @Transactional(propagation = NOT_SUPPORTED)} y que cada escritura vaya dentro de su propio
 * {@link TransactionTemplate}.</p>
 *
 * <p>Como consecuencia, lo que se escribe aquí se confirma de verdad, y el contenedor de
 * {@link PostgreSQLTestContainer} es un campo estático que comparten todas las clases de test de la
 * JVM. Por eso el {@code @AfterEach} limpia a mano: sin eso, estas filas se filtrarían a los demás
 * tests.</p>
 *
 * <p>Las aserciones no dependen de números de revisión absolutos, solo del orden y del número de
 * revisiones de cada entidad: la secuencia no se reinicia entre métodos.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class EnversAuditDataJpaTest extends PostgreSQLTestContainer {

    @DynamicPropertySource
    static void postgreSQLProperties(DynamicPropertyRegistry registry) {
        registerPostgreSQLProperties(registry);
    }

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;

    private TransactionTemplate transaction() {
        if (transaction == null) {
            transaction = new TransactionTemplate(transactionManager);
        }
        return transaction;
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        inTransaction(em -> em.createNativeQuery("""
                truncate table reservation_aud, assembly_component_aud, assembly_aud, project_aud,
                               warehouse_aud, supplier_aud, material_aud, audit_revision,
                               stock_movement, inventory_balance, reservation, assembly_component,
                               assembly, material, warehouse, project
                cascade
                """).executeUpdate());
    }

    @Test
    void updatingAMaterialRecordsARevisionWithTheAuthenticatedUsername() {
        authenticateAs("alejandro");
        UUID id = inTransactionReturning(em -> {
            Material saved = material("MAT-AUD-1", "Copper contact wire");
            em.persist(saved);
            return saved.getId();
        });

        inTransaction(em -> em.find(Material.class, id).setMinimumStockLevel(new BigDecimal("25.000000")));

        List<Number> revisions = reading(reader -> reader.getRevisions(Material.class, id));
        assertEquals(2, revisions.size());

        Material previous = reading(reader -> reader.find(Material.class, id, revisions.get(0)));
        Material current = reading(reader -> reader.find(Material.class, id, revisions.get(1)));
        assertEquals(0, BigDecimal.ZERO.compareTo(previous.getMinimumStockLevel()));
        assertEquals(0, new BigDecimal("25.000000").compareTo(current.getMinimumStockLevel()));

        AuditRevision revision = reading(reader -> reader.findRevision(AuditRevision.class, revisions.get(1)));
        assertEquals("alejandro", revision.getUsername());
        assertEquals("subject-alejandro", revision.getUserId());
        assertNotNull(revision.getRevisionInstant());
    }

    /**
     * Es el caso que motiva todo esto: el PUT de un conjunto reemplaza el despiece con
     * orphanRemoval, asi que sin historial una linea borrada no dejaba rastro en ningun sitio.
     */
    @Test
    void removingAnAssemblyComponentLeavesADeletionRevisionThatKeepsItsValues() {
        UUID assemblyId = inTransactionReturning(em -> {
            Material material = material("MAT-AUD-2", "Dropper");
            em.persist(material);
            Assembly assembly = Assembly.builder().code("ASM-AUD-1").name("Cantilever").build();
            AssemblyComponent component = AssemblyComponent.builder()
                    .material(material)
                    .quantity(new BigDecimal("4.000000"))
                    .build();
            audit(component);
            assembly.addComponent(component);
            audit(assembly);
            em.persist(assembly);
            return assembly.getId();
        });

        UUID componentId = inTransactionReturning(em -> {
            Assembly assembly = em.find(Assembly.class, assemblyId);
            AssemblyComponent component = assembly.getComponents().getFirst();
            UUID id = component.getId();
            assembly.removeComponent(component);
            return id;
        });

        List<Object[]> history = componentRevisions(componentId);
        assertEquals(2, history.size());
        assertEquals(RevisionType.DEL, history.get(1)[2]);

        // store_data_at_delete: la fila de borrado conserva lo que decia la linea, en lugar de dejar
        // solo el id. Es la diferencia entre "se borro algo" y "se borraron 4 unidades de este material".
        AssemblyComponent deleted = (AssemblyComponent) history.get(1)[0];
        assertEquals(0, new BigDecimal("4.000000").compareTo(deleted.getQuantity()));

        // Cambiar la lista de componentes revisa tambien el conjunto, aunque sus columnas no cambien:
        // revision_on_collection_change. Es deseable -el despiece cambio- pero sorprende, asi que se fija.
        assertEquals(2, reading(reader -> reader.getRevisions(Assembly.class, assemblyId)).size());
    }

    /**
     * La comprobacion de que reservation_aud.status es el tipo enum nativo y no varchar: si V7 se
     * hubiera escrito con varchar, esto no llegaria ni a ejecutarse porque el contexto no arrancaria.
     */
    @Test
    void reservationStatusChangeIsAuditedThroughThePostgresEnum() {
        UUID id = inTransactionReturning(em -> {
            Material material = material("MAT-AUD-3", "Messenger wire");
            Warehouse warehouse = warehouse("WH-AUD-1");
            Project project = project("PRJ-AUD-1");
            em.persist(material);
            em.persist(warehouse);
            em.persist(project);
            Reservation reservation = Reservation.builder()
                    .material(material)
                    .warehouse(warehouse)
                    .project(project)
                    .quantity(new BigDecimal("3.000000"))
                    .status(ReservationStatus.ACTIVE)
                    .build();
            audit(reservation);
            em.persist(reservation);
            return reservation.getId();
        });

        inTransaction(em -> em.find(Reservation.class, id).cancel(Instant.parse("2026-08-02T10:00:00Z")));

        List<Number> revisions = reading(reader -> reader.getRevisions(Reservation.class, id));
        assertEquals(2, revisions.size());
        assertEquals(ReservationStatus.ACTIVE,
                reading(reader -> reader.find(Reservation.class, id, revisions.get(0))).getStatus());

        Reservation cancelled = reading(reader -> reader.find(Reservation.class, id, revisions.get(1)));
        assertEquals(ReservationStatus.CANCELLED, cancelled.getStatus());
        assertNotNull(cancelled.getReleasedAt());
    }

    /**
     * Las tres exclusiones, comprobadas donde importa: en el esquema. Un {@code @Audited} que se
     * colara en AuditableEntity haria fallar esto antes de que nadie se pregunte por que hay una
     * tabla de historial vacia.
     */
    @Test
    void theLedgerTheProjectionAndTheInboxAreNotAudited() {
        List<String> auditTables = inTransactionReturning(em -> em.createNativeQuery("""
                select table_name from information_schema.tables
                 where table_schema = 'public' and table_name like '%\\_aud'
                 order by table_name
                """).getResultList().stream().map(String::valueOf).toList());

        assertEquals(List.of("assembly_aud", "assembly_component_aud", "material_aud", "project_aud",
                "reservation_aud", "supplier_aud", "warehouse_aud"), auditTables);

        assertFalse(auditTables.contains("stock_movement_aud"));
        assertFalse(auditTables.contains("inventory_balance_aud"));
        assertFalse(auditTables.contains("inbox_message_aud"));
    }

    /** Un movimiento del libro mayor no abre revision: ya es inmutable, el historial seria una copia. */
    @Test
    void writingAStockMovementCreatesNoRevision() {
        long revisionsBefore = revisionCount();

        inTransaction(em -> {
            Material material = material("MAT-AUD-4", "Steady arm");
            Warehouse warehouse = warehouse("WH-AUD-2");
            em.persist(material);
            em.persist(warehouse);
            em.flush();
            StockMovement movement = StockMovement.builder()
                    .material(material)
                    .warehouse(warehouse)
                    .type(StockMovementType.ENTRY)
                    .quantity(new BigDecimal("10.000000"))
                    .occurredAt(Instant.parse("2026-08-02T11:00:00Z"))
                    .build();
            audit(movement);
            em.persist(movement);
        });

        // El material y el almacen comparten una sola revision: Envers abre una por TRANSACCION, no
        // por entidad. El movimiento no anade ninguna, que es lo que se esta comprobando.
        assertEquals(revisionsBefore + 1, revisionCount());
        long movements = inTransactionReturning(em -> ((Number) em
                .createNativeQuery("select count(*) from stock_movement")
                .getSingleResult()).longValue());
        assertEquals(1L, movements);
    }

    /**
     * Una escritura de un proceso de fondo -sin peticion y sin usuario- se registra como
     * {@code system}, igual que en la columna updated_by. Las dos auditorias comparten resolutor
     * precisamente para que no puedan discrepar.
     */
    @Test
    void writesWithoutAnAuthenticatedUserAreRecordedAsSystem() {
        UUID id = inTransactionReturning(em -> {
            Material material = material("MAT-AUD-5", "Section insulator");
            em.persist(material);
            return material.getId();
        });

        Number revision = reading(reader -> reader.getRevisions(Material.class, id)).getFirst();
        AuditRevision auditRevision = reading(reader -> reader.findRevision(AuditRevision.class, revision));
        assertEquals("system", auditRevision.getUsername());
        assertEquals(AuditRevisionSource.SYSTEM, auditRevision.getSource());
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> componentRevisions(UUID componentId) {
        return reading(reader -> (List<Object[]>) reader.createQuery()
                .forRevisionsOfEntity(AssemblyComponent.class, false, true)
                .add(AuditEntity.id().eq(componentId))
                .addOrder(AuditEntity.revisionNumber().asc())
                .getResultList());
    }

    private long revisionCount() {
        return inTransactionReturning(em -> ((Number) em
                .createNativeQuery("select count(*) from audit_revision")
                .getSingleResult()).longValue());
    }

    /**
     * Toda lectura de auditoria va dentro de una transaccion: con la del test apagada, el
     * EntityManager compartido no esta ligado a ninguna, y el AuditReader necesita una sesion viva.
     */
    private <T> T reading(java.util.function.Function<AuditReader, T> work) {
        return inTransactionReturning(em -> work.apply(AuditReaderFactory.get(em)));
    }

    private void inTransaction(Consumer<EntityManager> work) {
        transaction().executeWithoutResult(status -> work.accept(entityManager));
    }

    private <T> T inTransactionReturning(java.util.function.Function<EntityManager, T> work) {
        return transaction().execute(status -> work.apply(entityManager));
    }

    private static void authenticateAs(String username) {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                Jwt.withTokenValue("token")
                        .header("alg", "RS256")
                        .subject("subject-" + username)
                        .claim("preferred_username", username)
                        .issuedAt(Instant.EPOCH)
                        .expiresAt(Instant.EPOCH.plusSeconds(300))
                        .build(),
                AuthorityUtils.createAuthorityList("ROLE_STOCK_WRITE"),
                username
        ));
    }

    /**
     * Un slice de {@code @DataJpaTest} no carga {@code JpaAuditingConfiguration}, asi que las
     * columnas de auditoria hay que rellenarlas a mano. El listener de Envers si funciona, porque no
     * depende del contenedor: esa es la diferencia entre las dos mitades de la auditoria.
     */
    private static void audit(AuditableEntity entity) {
        Instant now = Instant.parse("2026-08-01T08:00:00Z");
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setCreatedBy("audit-test");
        entity.setUpdatedBy("audit-test");
    }

    private static Material material(String code, String name) {
        Material material = Material.builder()
                .code(code)
                .name(name)
                .unitOfMeasure("unit")
                .minimumStockLevel(BigDecimal.ZERO)
                .build();
        audit(material);
        return material;
    }

    private static Warehouse warehouse(String code) {
        Warehouse warehouse = Warehouse.builder().code(code).name("Warehouse " + code).build();
        audit(warehouse);
        return warehouse;
    }

    private static Project project(String code) {
        Project project = Project.builder().code(code).name("Project " + code).build();
        audit(project);
        return project;
    }
}
