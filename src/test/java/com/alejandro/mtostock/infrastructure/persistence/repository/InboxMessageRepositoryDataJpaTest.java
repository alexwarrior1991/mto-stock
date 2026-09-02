package com.alejandro.mtostock.infrastructure.persistence.repository;

import com.alejandro.mtostock.infrastructure.persistence.entity.InboxMessage;
import com.alejandro.mtostock.infrastructure.persistence.entity.InboxMessageStatus;
import com.alejandro.mtostock.support.PostgreSQLTestContainer;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Comprobaciones del inbox contra PostgreSQL de verdad.
 *
 * <p>Están aquí y no entre los tests con dobles porque lo que garantiza la idempotencia no es el
 * servicio, es el esquema: la restricción única, el {@code on conflict} de la inserción y el
 * recuento de filas de los {@code update} condicionales. Un doble de repositorio devuelve lo que se
 * le diga y no probaría ninguna de las tres cosas.</p>
 *
 * <p>El mapeo de la entidad se ejercita de paso: {@code ddl-auto: validate} corre contra el esquema
 * que deja Flyway, así que una columna {@code jsonb} o el tipo enumerado que no cuadren con la
 * entidad hacen fallar el arranque de este test.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class InboxMessageRepositoryDataJpaTest extends PostgreSQLTestContainer {

    private static final String MESSAGE_ID = "0f8b1f4c-3f6a-4a6d-9a2a-1c9f5f6f2b10";
    private static final String SOURCE_SERVICE = "mto-configuration";
    private static final String PAYLOAD = """
            {"operationId":"0f8b1f4c-3f6a-4a6d-9a2a-1c9f5f6f2b10","eventType":"MASTER_DATA_STATION_UPDATED"}""";

    @DynamicPropertySource
    static void postgreSQLProperties(DynamicPropertyRegistry registry) {
        registerPostgreSQLProperties(registry);
    }

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private InboxMessageRepository inboxMessageRepository;

    @Test
    void firstDeliveryIsRecordedClaimedAndMarkedAsProcessedWithOneAttempt() {
        assertEquals(1, insert());
        assertEquals(1, inboxMessageRepository.claimForProcessing(MESSAGE_ID, SOURCE_SERVICE));
        assertEquals(1, inboxMessageRepository.markProcessed(MESSAGE_ID, SOURCE_SERVICE));

        InboxMessage stored = reload();
        assertEquals(InboxMessageStatus.PROCESSED, stored.getStatus());
        assertEquals(1, stored.getProcessingAttempts());
        assertNotNull(stored.getProcessedAt());
        assertNotNull(stored.getReceivedAt());
        assertEquals(PAYLOAD, stored.getPayload());
        assertTrue(stored.isProcessed());
    }

    /**
     * La segunda entrega no inserta nada y no consigue reclamar: ese 0 es la señal de duplicado con
     * la que el servicio se salta el trabajo, y el contador de intentos no se mueve.
     */
    @Test
    void redeliveryOfAnAppliedMessageNeitherInsertsNorClaims() {
        insert();
        inboxMessageRepository.claimForProcessing(MESSAGE_ID, SOURCE_SERVICE);
        inboxMessageRepository.markProcessed(MESSAGE_ID, SOURCE_SERVICE);

        assertEquals(0, insert());
        assertEquals(0, inboxMessageRepository.claimForProcessing(MESSAGE_ID, SOURCE_SERVICE));

        InboxMessage stored = reload();
        assertEquals(InboxMessageStatus.PROCESSED, stored.getStatus());
        assertEquals(1, stored.getProcessingAttempts());
        assertEquals(1, inboxMessageRepository.findByStatus(InboxMessageStatus.PROCESSED).size());
    }

    /**
     * Dos entregas simultáneas del mismo mensaje no pueden crear dos filas: la unicidad la impone la
     * base de datos, no el código. Con {@code on conflict do nothing} la segunda inserción no
     * revienta, simplemente no inserta.
     */
    @Test
    void theUniqueConstraintKeepsASingleRowPerMessage() {
        assertEquals(1, insert());
        assertEquals(0, insert());

        assertEquals(1, inboxMessageRepository.count());
    }

    /**
     * El fallo se registra con una inserción {@code on conflict do update} porque en la primera
     * entrega la transacción del intento revierte y se lleva la fila: no hay nada que actualizar.
     */
    @Test
    void failureOfANeverRecordedMessageInsertsItAsFailedWithOneAttempt() {
        assertEquals(1, recordFailure("java.lang.IllegalStateException: database is down"));

        InboxMessage stored = reload();
        assertEquals(InboxMessageStatus.FAILED, stored.getStatus());
        assertEquals(1, stored.getProcessingAttempts());
        assertNotNull(stored.getFailedAt());
        assertTrue(stored.getFailureReason().contains("database is down"));
        assertNull(stored.getProcessedAt());
    }

    /**
     * Reintento controlado: un mensaje fallido se vuelve a reclamar -su estado no es PROCESSED- el
     * contador sigue subiendo y el motivo del fallo anterior se limpia, para que un mensaje aplicado
     * con éxito no aparezca con un error al lado.
     */
    @Test
    void aFailedMessageIsClaimedAgainAndCanEndUpProcessed() {
        insert();
        inboxMessageRepository.claimForProcessing(MESSAGE_ID, SOURCE_SERVICE);
        recordFailure("java.lang.IllegalStateException: database is down");
        assertEquals(2, reload().getProcessingAttempts());

        assertEquals(1, inboxMessageRepository.claimForProcessing(MESSAGE_ID, SOURCE_SERVICE));
        assertEquals(1, inboxMessageRepository.markProcessed(MESSAGE_ID, SOURCE_SERVICE));

        InboxMessage stored = reload();
        assertEquals(InboxMessageStatus.PROCESSED, stored.getStatus());
        assertEquals(3, stored.getProcessingAttempts());
        assertNull(stored.getFailureReason());
        assertNull(stored.getFailedAt());
        assertNotNull(stored.getProcessedAt());
    }

    /** Marcar como aplicado solo vale sobre lo que esta entrega reclamó. */
    @Test
    void markingAsProcessedDoesNothingWhenTheMessageWasNotClaimed() {
        insert();

        assertEquals(0, inboxMessageRepository.markProcessed(MESSAGE_ID, SOURCE_SERVICE));
        assertEquals(InboxMessageStatus.RECEIVED, reload().getStatus());
    }

    private int insert() {
        int inserted = inboxMessageRepository.insertIfMissing(
                MESSAGE_ID,
                SOURCE_SERVICE,
                "MASTER_DATA_STATION_UPDATED",
                "station",
                "42",
                "mto.master-data.exchange",
                "mto.master-data.station.updated",
                "mto.stock.master-data.queue",
                "9f2c1b0d",
                PAYLOAD);
        entityManager.clear();
        return inserted;
    }

    private int recordFailure(String reason) {
        int rows = inboxMessageRepository.recordFailure(
                MESSAGE_ID,
                SOURCE_SERVICE,
                "MASTER_DATA_STATION_UPDATED",
                "station",
                "42",
                "mto.master-data.exchange",
                "mto.master-data.station.updated",
                "mto.stock.master-data.queue",
                "9f2c1b0d",
                PAYLOAD,
                reason);
        entityManager.clear();
        return rows;
    }

    /**
     * Las escrituras son sentencias nativas, que no pasan por el contexto de persistencia: sin
     * limpiarlo, una entidad ya cargada se devolvería con el estado anterior y el test comprobaría
     * la caché en vez de la tabla.
     */
    private InboxMessage reload() {
        entityManager.clear();
        Optional<InboxMessage> stored =
                inboxMessageRepository.findByMessageIdAndSourceService(MESSAGE_ID, SOURCE_SERVICE);
        assertTrue(stored.isPresent());
        return stored.get();
    }
}
