package com.alejandro.mtostock.configuration.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Invalidates cache entries once the transaction that changed the row has committed.
 *
 * <h2>Por qué no vale {@code @CacheEvict}</h2>
 *
 * <p>{@code @CacheEvict}, con el {@code beforeInvocation = false} de por defecto, se ejecuta al
 * <b>volver el método</b>, que sigue siendo <b>antes</b> de que la transacción confirme. Entre esos
 * dos instantes caben dos cosas malas:</p>
 *
 * <ul>
 *   <li>otra petición lee de Postgres el valor viejo —que todavía es el comprometido— y vuelve a
 *       llenar la caché con él, donde se queda hasta que expire el TTL;</li>
 *   <li>si la transacción se deshace, la caché ya se ha tirado por un cambio que nunca ocurrió.</li>
 * </ul>
 *
 * <p>Lo primero es lo que de verdad importa: convierte una ventana de milisegundos en media hora de
 * dato rancio, y con dos peticiones concurrentes sobre el mismo material no es un caso raro. Ambas
 * se cierran invalidando después del <i>commit</i>.</p>
 *
 * <p>Fuera de una transacción se invalida en el acto: no hay ningún commit que esperar. Es lo que
 * pasa en un test que llame al servicio directamente.</p>
 *
 * <h2>Con la caché apagada</h2>
 *
 * <p>El bean existe siempre, tenga o no la aplicación una caché detrás, para que los servicios
 * llamen a este colaborador sin preguntar si la caché está encendida. Con
 * {@code app.cache.enabled} en {@code false} el {@link CacheManager} del contexto es un
 * {@code NoOpCacheManager}, así que las llamadas no hacen nada.</p>
 *
 * <p>Se pide por {@link ObjectProvider} y no como dependencia normal para el caso en el que no haya
 * <b>ningún</b> {@code CacheManager}: un slice de test que no arrastre {@link CacheConfiguration}.
 * Ahí una dependencia obligatoria impediría crear el bean, y con ella los servicios que dependen
 * de él.</p>
 *
 * <p>Los fallos se registran y se tragan, por el mismo motivo que en {@link LoggingCacheErrorHandler}:
 * a estas alturas la escritura en Postgres ya está confirmada y no hay nada que deshacer, así que
 * dejar subir la excepción solo convertiría una caché rancia en un error para el cliente. Además, a
 * un {@code afterCommit} no le queda ya nadie a quien propagarle nada.</p>
 */
@Component
public class CacheInvalidator {

    private static final Logger LOGGER = LoggerFactory.getLogger(CacheInvalidator.class);

    private final ObjectProvider<CacheManager> cacheManager;

    public CacheInvalidator(ObjectProvider<CacheManager> cacheManager) {
        this.cacheManager = cacheManager;
    }

    /** Invalida una entrada concreta cuando confirme la transacción en curso. */
    public void evictAfterCommit(String cacheName, Object key) {
        afterCommit(() -> evict(cacheName, key));
    }

    /**
     * Invalida la caché entera cuando confirme la transacción en curso.
     *
     * <p>Para quien cambia una fila sin conocer su id: las escrituras nativas de
     * {@code ProjectRepository} devuelven un contador de filas, no el proyecto tocado, así que no
     * hay clave que invalidar. Son eventos poco frecuentes y el coste es un puñado de fallos de
     * caché.</p>
     */
    public void evictAllAfterCommit(String cacheName) {
        afterCommit(() -> evictAll(cacheName));
    }

    private void afterCommit(Runnable invalidation) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            invalidation.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                invalidation.run();
            }
        });
    }

    private void evict(String cacheName, Object key) {
        Cache cache = cache(cacheName);
        if (cache == null) {
            return;
        }
        try {
            cache.evict(key);
        } catch (RuntimeException exception) {
            LOGGER.warn("Cache entry could not be evicted after commit: cache={}, key={}, cause={}",
                    cacheName, key, exception.toString());
        }
    }

    private void evictAll(String cacheName) {
        Cache cache = cache(cacheName);
        if (cache == null) {
            return;
        }
        try {
            cache.clear();
        } catch (RuntimeException exception) {
            LOGGER.warn("Cache could not be cleared after commit: cache={}, cause={}",
                    cacheName, exception.toString());
        }
    }

    /** {@code null} cuando la caché está apagada: no hay {@code CacheManager} en el contexto. */
    private Cache cache(String cacheName) {
        CacheManager manager = cacheManager.getIfAvailable();
        return manager == null ? null : manager.getCache(cacheName);
    }
}
