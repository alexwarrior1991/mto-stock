package com.alejandro.mtostock.configuration.idempotency;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * How long an {@code Idempotency-Key} is remembered.
 *
 * <p>Es el contrato con los clientes: una clave usada hace más de esto se olvida, y un reintento con
 * ella es una petición nueva. Tiene que sobrar frente a lo que un cliente puede tardar en reintentar;
 * {@code mto-maintenance} reintenta solo lo que se quedó sin respuesta cada pocos minutos mientras
 * stock le responde.</p>
 *
 * <p>El interruptor de la purga ({@code app.idempotency.purge.enabled}) y su horario
 * ({@code app.idempotency.purge.cron}) no están aquí: los leen {@code @ConditionalOnProperty} y
 * {@code @Scheduled} en {@link IdempotencyPurgeConfiguration}, como en {@code CacheProperties}. Un
 * plazo vacío, cero o negativo se sustituye por el de por defecto: olvidar una clave en el acto
 * dejaría a los clientes sin la protección que la clave promete.</p>
 */
@Validated
@ConfigurationProperties(prefix = "app.idempotency")
public record IdempotencyProperties(
        @NotNull Duration retention
) {

    static final Duration DEFAULT_RETENTION = Duration.ofDays(30);

    public IdempotencyProperties {
        retention = retention == null || retention.isNegative() || retention.isZero() ? DEFAULT_RETENTION : retention;
    }
}
