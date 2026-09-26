package com.alejandro.mtostock.configuration.idempotency;

import com.alejandro.mtostock.application.service.IdempotentRequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;

/**
 * Forgets the {@code Idempotency-Key}s older than {@code app.idempotency.retention}, once a day.
 *
 * <p>Encendida por defecto, al revés que la caché: sin ella {@code idempotent_request} crece con cada
 * reserva y cada salida con clave. Los tests la apagan ({@code app.idempotency.purge.enabled=false})
 * para que ningún hilo borre filas por su cuenta mientras corren, y prueban la purga llamándola.</p>
 *
 * <p>Con varias instancias, todas purgan a la misma hora y sin coordinarse: borrar dos veces lo mismo
 * no hace daño, la que llega después encuentra las filas ya borradas.</p>
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(IdempotencyProperties.class)
@ConditionalOnProperty(prefix = "app.idempotency.purge", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class IdempotencyPurgeConfiguration {

    private final IdempotentRequestService idempotentRequestService;
    private final IdempotencyProperties properties;

    @Scheduled(cron = "${app.idempotency.purge.cron:0 17 3 * * *}")
    public void purgeExpiredKeys() {
        idempotentRequestService.purgeClaimedBefore(Instant.now().minus(properties.retention()));
    }
}
