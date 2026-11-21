package com.alejandro.mtostock.configuration.inbox;

import com.alejandro.mtostock.application.service.InboxPurgeService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Registra las properties del inbox y, si esta encendida, su purga diaria.
 *
 * <p>Las properties se registran siempre porque el servicio que purga existe siempre (los tests lo
 * llaman). Encendida por defecto: sin ella {@code inbox_message} crece con cada evento consumido, con su
 * JSON entero. Los tests la apagan ({@code app.inbox.purge.enabled=false}) para que ningun hilo borre
 * filas por su cuenta mientras corren.</p>
 *
 * <p>Con varias instancias, todas purgan a la misma hora y sin coordinarse: cada lote se salta las filas
 * que otra tiene bloqueadas, y la que llega despues encuentra las demas ya borradas.</p>
 */
@Configuration
@EnableConfigurationProperties(InboxProperties.class)
public class InboxPurgeConfiguration {

    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(prefix = "app.inbox.purge", name = "enabled", havingValue = "true", matchIfMissing = true)
    @RequiredArgsConstructor
    static class Scheduler {

        private static final Logger LOGGER = LoggerFactory.getLogger(Scheduler.class);

        private final InboxPurgeService inboxPurgeService;

        @Scheduled(cron = "${app.inbox.purge.cron:0 43 3 * * *}")
        public void purgeOldMessages() {
            try {
                inboxPurgeService.purge();
            } catch (RuntimeException exception) {
                // Manana vuelve a intentarse, y lo ya borrado en los lotes anteriores esta confirmado.
                LOGGER.error("The purge of the inbox failed", exception);
            }
        }
    }
}
