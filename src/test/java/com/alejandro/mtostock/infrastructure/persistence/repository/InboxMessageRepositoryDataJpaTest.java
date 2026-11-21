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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
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

    /**
     * La purga borra por lotes los aplicados hace mas del plazo, contado desde que se aplicaron: el que
     * llego hace meses pero se aplico ayer (fallo, y alguien lo reencolo) se recuerda. Lo reciente, lo
     * fallido y lo que no llego a aplicarse no se tocan.
     */
    @Test
    void thePurgeForgetsInBatchesWhatWasAppliedLongAgoAndNothingElse() {
        for (int i = 0; i < 5; i++) {
            processed("old-" + i, "100 days", "100 days");
        }
        processed("late", "100 days", "1 day");
        processed("recent", "1 day", "1 day");
        failed("failed-old", "100 days", "100 days");
        insert("received-old");
        age("received-old", "100 days");
        Instant before = Instant.now().minus(Duration.ofDays(30));

        assertEquals(2, inboxMessageRepository.purgeProcessedBefore(before, 2), "A full batch: there may be more");
        assertEquals(2, inboxMessageRepository.purgeProcessedBefore(before, 2));
        assertEquals(1, inboxMessageRepository.purgeProcessedBefore(before, 2), "A short batch: that was all");
        assertEquals(0, inboxMessageRepository.purgeProcessedBefore(before, 2));

        entityManager.clear();
        assertEquals(List.of("failed-old", "late", "received-old", "recent"), ownMessageIds());
        assertEquals(1, insert("old-0"), "A purged message is forgotten: if it came again it would be the first time");
    }

    /**
     * Las fallidas tienen su plazo, contado desde el ultimo fallo, y su purga no toca las aplicadas. Una
     * fallida purgada que alguien reencola despues se inserta y se reclama como nueva.
     */
    @Test
    void thePurgeForgetsWhatFailedLongAgoByItsLastFailure() {
        failed("failed-old", "200 days", "200 days");
        failed("failed-again", "200 days", "10 days");
        processed("processed-old", "200 days", "200 days");
        Instant before = Instant.now().minus(Duration.ofDays(90));

        assertEquals(1, inboxMessageRepository.purgeFailedBefore(before, 500));

        entityManager.clear();
        assertEquals(List.of("failed-again", "processed-old"), ownMessageIds());
        assertEquals(1, insert("failed-old"));
        assertEquals(1, inboxMessageRepository.claimForProcessing("failed-old", SOURCE_SERVICE));
    }

    /** Los mensajes de este test: la base la comparten las demas clases. */
    private List<String> ownMessageIds() {
        return inboxMessageRepository.findAll().stream().map(InboxMessage::getMessageId)
                .filter(id -> id.matches("(old-\\d|late|recent|received-old|processed-old|failed-old|failed-again)")).sorted().toList();
    }

    private void processed(String messageId, String receivedAgo, String processedAgo) {
        insert(messageId);
        inboxMessageRepository.claimForProcessing(messageId, SOURCE_SERVICE);
        inboxMessageRepository.markProcessed(messageId, SOURCE_SERVICE);
        age(messageId, receivedAgo);
        entityManager.createNativeQuery("update inbox_message set processed_at = now() - cast(:ago as interval) where message_id = :id")
                .setParameter("ago", processedAgo).setParameter("id", messageId).executeUpdate();
    }

    private void failed(String messageId, String receivedAgo, String failedAgo) {
        inboxMessageRepository.recordFailure(messageId, SOURCE_SERVICE, "MASTER_DATA_STATION_UPDATED", "station", "42",
                "mto.master-data.exchange", "mto.master-data.station.updated", "mto.stock.master-data.queue", "9f2c1b0d",
                PAYLOAD, "java.lang.IllegalStateException: database is down");
        age(messageId, receivedAgo);
        entityManager.createNativeQuery("update inbox_message set failed_at = now() - cast(:ago as interval) where message_id = :id")
                .setParameter("ago", failedAgo).setParameter("id", messageId).executeUpdate();
    }

    private void age(String messageId, String receivedAgo) {
        entityManager.createNativeQuery("update inbox_message set received_at = now() - cast(:ago as interval) where message_id = :id")
                .setParameter("ago", receivedAgo).setParameter("id", messageId).executeUpdate();
    }

    private int insert() {
        return insert(MESSAGE_ID);
    }

    private int insert(String messageId) {
        int inserted = inboxMessageRepository.insertIfMissing(
                messageId,
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
