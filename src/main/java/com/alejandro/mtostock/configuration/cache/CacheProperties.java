package com.alejandro.mtostock.configuration.cache;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Time to live and key prefix of the master data caches.
 *
 * <p>El interruptor <b>no</b> está aquí: {@code app.cache.enabled} lo lee el
 * {@code @ConditionalOnProperty} de {@link CacheConfiguration}, que es quien decide si los beans
 * existen. Tenerlo además como campo obligaría a mantener dos lecturas del mismo interruptor, y la
 * que no manda es justo la que acaba mintiendo. Es el mismo reparto que en
 * {@code MasterDataRabbitProperties}.</p>
 *
 * <p>Un valor en blanco o sin sentido se sustituye por el de por defecto en lugar de rechazarse:
 * una variable de entorno declarada y vacía es el caso normal de un despliegue a medio configurar,
 * y un TTL de cero segundos —una caché que no cachea nada pero paga cada ida y vuelta a Redis— es
 * peor que el valor razonable.</p>
 */
@Validated
@ConfigurationProperties(prefix = "app.cache")
public record CacheProperties(
        @NotNull Duration defaultTtl,
        @NotBlank String keyPrefix
) {

    static final Duration DEFAULT_TTL = Duration.ofMinutes(30);

    /**
     * El prefijo lleva los dos puntos al final a propósito: es el separador que espera
     * {@code redis-cli --scan} y el que agrupa las claves en cualquier visor. Importa porque el
     * Redis de un entorno local puede ser el mismo que usa {@code mto-configuration}, y sin prefijo
     * una caché llamada {@code projects} en los dos servicios sería la misma clave.
     */
    static final String DEFAULT_KEY_PREFIX = "mto-stock:";

    public CacheProperties {
        defaultTtl = defaultTtl == null || defaultTtl.isNegative() || defaultTtl.isZero()
                ? DEFAULT_TTL
                : defaultTtl;
        keyPrefix = keyPrefix == null || keyPrefix.isBlank() ? DEFAULT_KEY_PREFIX : keyPrefix;
    }
}
