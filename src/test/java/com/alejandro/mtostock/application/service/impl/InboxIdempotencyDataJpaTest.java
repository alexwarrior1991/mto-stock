package com.alejandro.mtostock.application.service.impl;

import com.alejandro.mtostock.application.dto.messaging.InboxMessageCommand;
import com.alejandro.mtostock.application.dto.messaging.InboxProcessingResult;
import com.alejandro.mtostock.application.service.InboxMessageService;
import com.alejandro.mtostock.infrastructure.persistence.entity.InboxMessage;
import com.alejandro.mtostock.infrastructure.persistence.entity.InboxMessageStatus;
import com.alejandro.mtostock.infrastructure.persistence.repository.InboxMessageRepository;
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

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * La garantía que da nombre al patrón, comprobada de punta a punta contra PostgreSQL.
 *
 * <p>Está aquí, en el paquete del servicio, y no entre los tests de repositorio, porque lo que se
 * prueba es el servicio completo sobre SQL real. Con un doble de repositorio no probaría nada: la
 * idempotencia no la decide el código, la decide la restricción única de la tabla, y un doble
 * devuelve lo que se le diga.</p>
 *
 * <p>Lo que no cabe aquí es la carrera entre dos entregas <b>simultáneas</b>: haría falta mantener
 * dos transacciones abiertas a la vez sobre dos conexiones, y un test transaccional tiene una. Esa
 * espera la aporta PostgreSQL —bloqueando a la segunda entrega en el índice único mientras la
 * primera no ha confirmado, o en la fila si ya estaba confirmada—; lo que se comprueba aquí es lo
 * que ve la segunda entrega cuando la primera ya terminó, que es el resultado de esa espera.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class InboxIdempotencyDataJpaTest extends PostgreSQLTestContainer {

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
    void theWorkRunsOnceAcrossTwoDeliveriesOfTheSameMessage() {
        InboxMessageService service = new InboxMessageServiceImpl(inboxMessageRepository);
        AtomicInteger executions = new AtomicInteger();

        InboxProcessingResult first = service.process(command(), executions::incrementAndGet);
        entityManager.clear();
        InboxProcessingResult second = service.process(command(), executions::incrementAndGet);

        assertEquals(InboxProcessingResult.PROCESSED, first);
        assertEquals(InboxProcessingResult.DUPLICATE_SKIPPED, second);
        assertEquals(1, executions.get());

        InboxMessage stored = reload();
        assertEquals(InboxMessageStatus.PROCESSED, stored.getStatus());
        // El duplicado no gasta intento: no llega a reclamar nada.
        assertEquals(1, stored.getProcessingAttempts());
        assertEquals(1, inboxMessageRepository.count());
    }

    /**
     * Ciclo completo de fallo y reintento. El estado fallido lo escribe {@code recordFailure} en su
     * propia transacción porque la del intento revierte y se lo llevaría por delante.
     */
    @Test
    void aFailedMessageIsRecordedAndCanBeRetriedUntilItSucceeds() {
        InboxMessageService service = new InboxMessageServiceImpl(inboxMessageRepository);
        RuntimeException failure = new IllegalStateException("database is down");

        assertThrows(IllegalStateException.class, () -> service.process(command(), () -> {
            throw failure;
        }));
        service.recordFailure(command(), failure);
        entityManager.clear();

        InboxMessage afterFailure = reload();
        assertEquals(InboxMessageStatus.FAILED, afterFailure.getStatus());
        assertTrue(afterFailure.getFailureReason().contains("database is down"));

        AtomicInteger executions = new AtomicInteger();
        InboxProcessingResult retry = service.process(command(), executions::incrementAndGet);
        entityManager.clear();

        assertEquals(InboxProcessingResult.PROCESSED, retry);
        assertEquals(1, executions.get());
        assertEquals(InboxMessageStatus.PROCESSED, reload().getStatus());
    }

    private static InboxMessageCommand command() {
        return new InboxMessageCommand(MESSAGE_ID, SOURCE_SERVICE, "MASTER_DATA_STATION_UPDATED", "station",
                "42", "mto.master-data.exchange", "mto.master-data.station.updated",
                "mto.stock.master-data.queue", "9f2c1b0d", PAYLOAD);
    }

    private InboxMessage reload() {
        entityManager.clear();
        return inboxMessageRepository.findByMessageIdAndSourceService(MESSAGE_ID, SOURCE_SERVICE).orElseThrow();
    }
}
