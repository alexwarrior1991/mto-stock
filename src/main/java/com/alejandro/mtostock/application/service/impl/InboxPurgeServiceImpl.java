package com.alejandro.mtostock.application.service.impl;

import com.alejandro.mtostock.application.service.InboxPurgeService;
import com.alejandro.mtostock.configuration.inbox.InboxProperties;
import com.alejandro.mtostock.infrastructure.persistence.repository.InboxMessageRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.function.IntSupplier;

/**
 * La purga del inbox de {@link InboxPurgeService}.
 *
 * <p>Sin {@code @Transactional} a proposito: cada lote es la transaccion de su sentencia, para que una
 * purga grande no retenga miles de filas bloqueadas hasta el final y lo borrado antes de un fallo quede
 * borrado. Lo que borra se cuenta en {@code inbox.messages.purged} (etiqueta {@code status}) y se dice
 * en una linea de log.</p>
 */
@Service
class InboxPurgeServiceImpl implements InboxPurgeService {

    private static final Logger LOGGER = LoggerFactory.getLogger(InboxPurgeServiceImpl.class);

    static final String PURGED_METER = "inbox.messages.purged";

    private final InboxMessageRepository inboxMessageRepository;
    private final InboxProperties properties;
    private final Counter purgedProcessed;
    private final Counter purgedFailed;

    InboxPurgeServiceImpl(InboxMessageRepository inboxMessageRepository, InboxProperties properties, MeterRegistry meterRegistry) {
        this.inboxMessageRepository = inboxMessageRepository;
        this.properties = properties;
        this.purgedProcessed = Counter.builder(PURGED_METER).tag("status", "PROCESSED")
                .description("Inbox messages deleted by the purge").register(meterRegistry);
        this.purgedFailed = Counter.builder(PURGED_METER).tag("status", "FAILED")
                .description("Inbox messages deleted by the purge").register(meterRegistry);
    }

    @Override
    public Purged purge() {
        Instant now = Instant.now();
        Instant processedBefore = now.minus(properties.retention());
        Instant failedBefore = now.minus(properties.failedRetention());
        int batchSize = properties.purge().batchSize();

        int processed = inBatches(batchSize, () -> inboxMessageRepository.purgeProcessedBefore(processedBefore, batchSize));
        purgedProcessed.increment(processed);
        int failed = inBatches(batchSize, () -> inboxMessageRepository.purgeFailedBefore(failedBefore, batchSize));
        purgedFailed.increment(failed);

        if (processed > 0 || failed > 0) {
            LOGGER.info("Inbox purged: {} messages applied before {} and {} failed before {}", processed, processedBefore,
                    failed, failedBefore);
        }
        return new Purged(processed, failed);
    }

    private static int inBatches(int batchSize, IntSupplier deleteBatch) {
        int total = 0;
        int deleted;
        do {
            deleted = deleteBatch.getAsInt();
            total += deleted;
        } while (deleted == batchSize);
        return total;
    }
}
