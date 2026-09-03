package com.alejandro.mtostock.configuration.cache;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Comprobaciones de la caché que no necesitan un Redis levantado: cuándo se invalida, qué
 * {@code CacheManager} hay según el interruptor, y que un Redis caído no rompe una petición.
 *
 * <p>Ninguna levanta Redis a propósito, igual que {@code MessagingLayerTest} no levanta un broker.
 * Construir un {@code RedisCacheManager} no abre ninguna conexión —Lettuce conecta de forma
 * perezosa—, así que el cableado se comprueba entero sin contenedor. Para el caso del Redis caído
 * se apunta a un puerto donde no escucha nadie, que es más fiel que apagar un contenedor: la
 * conexión se rechaza en el acto y el test no depende de ningún tiempo de espera.</p>
 */
class CacheLayerTest {

    private static final String KEY = "3f0c9d2e-0000-4000-8000-000000000001";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, DataRedisAutoConfiguration.class))
            .withUserConfiguration(CacheConfiguration.class, CacheInvalidator.class);

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /**
     * El corazón del asunto. Invalidar al volver el método —que es lo que hace un {@code @CacheEvict}
     * normal— deja la transacción todavía abierta: entre la invalidación y el commit cabe una lectura
     * que va a la base de datos, encuentra el valor viejo (que es lo correcto en read committed) y
     * vuelve a llenar la caché con él, donde se queda hasta que caduque. Aplazarlo al commit es lo
     * único que cierra esa ventana.
     */
    @Test
    void invalidationWaitsForTheCommitInsteadOfHappeningOnReturn() {
        Cache cache = mock(Cache.class);
        CacheInvalidator invalidator = invalidatorFor(cache);
        TransactionSynchronizationManager.initSynchronization();

        invalidator.evictAfterCommit(CacheNames.MATERIALS, KEY);

        verify(cache, never()).evict(KEY);

        commit();

        verify(cache).evict(KEY);
    }

    /** Lo mismo para la caché entera, que es como invalida el manejador de datos maestros. */
    @Test
    void wholeCacheInvalidationAlsoWaitsForTheCommit() {
        Cache cache = mock(Cache.class);
        CacheInvalidator invalidator = invalidatorFor(cache);
        TransactionSynchronizationManager.initSynchronization();

        invalidator.evictAllAfterCommit(CacheNames.PROJECTS);

        verify(cache, never()).clear();

        commit();

        verify(cache).clear();
    }

    /**
     * Si la transacción se deshace no hay nada que invalidar: el cambio que dejaba obsoleta la
     * entrada nunca llegó a aplicarse, y tirarla habría sido perder una entrada correcta.
     */
    @Test
    void invalidationNeverHappensWhenTheTransactionRollsBack() {
        Cache cache = mock(Cache.class);
        CacheInvalidator invalidator = invalidatorFor(cache);
        TransactionSynchronizationManager.initSynchronization();

        invalidator.evictAfterCommit(CacheNames.MATERIALS, KEY);
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(cache, never()).evict(any());
    }

    /** Fuera de una transacción no hay ningún commit que esperar. */
    @Test
    void invalidationIsImmediateOutsideATransaction() {
        Cache cache = mock(Cache.class);

        invalidatorFor(cache).evictAfterCommit(CacheNames.MATERIALS, KEY);

        verify(cache).evict(KEY);
    }

    /**
     * Con la caché apagada no hay {@code CacheManager} de Redis que consultar. El colaborador existe
     * igual —los servicios lo llaman sin preguntar— y no puede estallar por ello.
     */
    @Test
    void invalidationDoesNothingWhenThereIsNoCacheManager() {
        assertDoesNotThrow(() -> {
            new CacheInvalidator(emptyProvider()).evictAfterCommit(CacheNames.MATERIALS, KEY);
            new CacheInvalidator(emptyProvider()).evictAllAfterCommit(CacheNames.PROJECTS);
        });
    }

    /**
     * Un fallo al invalidar se registra y se traga. A estas alturas la escritura en PostgreSQL ya
     * está confirmada y no hay nada que deshacer: dejar subir la excepción solo convertiría una
     * caché rancia en un error para el cliente, y desde un {@code afterCommit} no queda ya nadie a
     * quien propagársela. La entrada obsoleta caduca sola por TTL.
     */
    @Test
    void invalidationFailureIsSwallowedInsteadOfBreakingTheWrite() {
        Cache cache = mock(Cache.class);
        doThrow(new IllegalStateException("Redis is down")).when(cache).evict(KEY);
        doThrow(new IllegalStateException("Redis is down")).when(cache).clear();
        CacheInvalidator invalidator = invalidatorFor(cache);

        assertDoesNotThrow(() -> invalidator.evictAfterCommit(CacheNames.MATERIALS, KEY));
        assertDoesNotThrow(() -> invalidator.evictAllAfterCommit(CacheNames.PROJECTS));
    }

    /** Sin estos beans nadie abre una conexion, asi que la aplicacion arranca sin Redis. */
    @Test
    void cacheManagerIsNoOpWhenTheCacheIsDisabled() {
        contextRunner.run(context -> {
            assertInstanceOf(NoOpCacheManager.class, context.getBean(CacheManager.class));
            assertNotNull(context.getBean(CacheInvalidator.class));
        });
    }

    /**
     * El {@code NoOpCacheManager} no es «no hay caché»: el interceptor sigue evaluando las
     * expresiones SpEL de cada anotación aunque no guarde nada, y por eso una clave mal escrita
     * revienta en esta suite —que corre sin Redis— en vez de esperar al primer entorno que la
     * encienda.
     */
    @Test
    void cachedMethodStillRunsWithTheNoOpManager() {
        contextRunner.withUserConfiguration(CachedProbeConfiguration.class).run(context -> {
            CachedProbe probe = context.getBean(CachedProbe.class);
            CachedProbe.CALLS.set(0);
            UUID id = UUID.randomUUID();

            assertEquals("value-" + id, probe.findById(id));
            assertEquals("value-" + id, probe.findById(id));

            assertEquals(2, CachedProbe.CALLS.get(), "el NoOp no guarda nada, asi que no hay aciertos");
        });
    }

    @Test
    void cacheManagerIsRedisBackedWhenEnabled() {
        contextRunner.withPropertyValues("app.cache.enabled=true").run(context -> {
            CacheManager manager = context.getBean(CacheManager.class);
            assertInstanceOf(RedisCacheManager.class, manager);
            CacheNames.ALL.forEach(name -> assertNotNull(manager.getCache(name), name));
        });
    }

    /**
     * Una errata en un {@code @Cacheable} no puede abrir en silencio una caché paralela: nadie la
     * invalidaría nunca, y eso se descubre sirviendo datos viejos.
     */
    @Test
    void unknownCacheNameIsNotCreatedOnTheFly() {
        contextRunner.withPropertyValues("app.cache.enabled=true")
                .run(context -> assertNull(context.getBean(CacheManager.class).getCache("materiales")));
    }

    /**
     * La comprobación que justifica el {@link LoggingCacheErrorHandler}: con Redis inalcanzable, un
     * método cacheado tiene que seguir devolviendo su valor —contra la base de datos— en lugar de
     * lanzar. Sin el manejador, el comportamiento por defecto de Spring es propagar, y un Redis
     * caído se convertiría en un 500 en todos los endpoints cacheados a la vez.
     */
    @Test
    void unreachableRedisFallsThroughToTheDatabaseInsteadOfFailing() {
        contextRunner.withUserConfiguration(CachedProbeConfiguration.class)
                .withPropertyValues(
                        "app.cache.enabled=true",
                        "spring.data.redis.host=localhost",
                        // Un puerto donde no escucha nadie: la conexion se rechaza en el acto.
                        "spring.data.redis.port=1",
                        "spring.data.redis.timeout=200ms",
                        "spring.data.redis.connect-timeout=200ms")
                .run(context -> {
                    assertInstanceOf(LoggingCacheErrorHandler.class,
                            context.getBean(CacheConfiguration.class).errorHandler());

                    CachedProbe probe = context.getBean(CachedProbe.class);
                    CachedProbe.CALLS.set(0);
                    UUID id = UUID.randomUUID();

                    assertEquals("value-" + id, probe.findById(id));
                    assertEquals("value-" + id, probe.findById(id));

                    assertEquals(2, CachedProbe.CALLS.get(), "sin cache utilizable se llama al metodo, pero no se lanza");
                });
    }

    /** El manejador de errores tiene que llegar por CachingConfigurer: un @Bean suelto no lo mira nadie. */
    @Test
    void errorHandlerIsExposedThroughTheCachingConfigurer() {
        contextRunner.run(context -> {
            CacheErrorHandler handler = context.getBean(CacheErrorHandler.class);
            assertInstanceOf(LoggingCacheErrorHandler.class, handler);
            // Los cuatro fallos se tragan: es lo que hace que la llamada siga contra la base de datos.
            Cache cache = mock(Cache.class);
            when(cache.getName()).thenReturn(CacheNames.MATERIALS);
            RuntimeException failure = new IllegalStateException("Redis is down");
            assertDoesNotThrow(() -> {
                handler.handleCacheGetError(failure, cache, KEY);
                handler.handleCachePutError(failure, cache, KEY, "value");
                handler.handleCacheEvictError(failure, cache, KEY);
                handler.handleCacheClearError(failure, cache);
            });
        });
    }

    /** Una variable de entorno declarada y vacia es el caso normal de un despliegue a medio configurar. */
    @Test
    void blankOrMeaninglessCachePropertiesFallBackToTheContract() {
        CacheProperties blank = new CacheProperties(null, "  ");

        assertEquals(CacheProperties.DEFAULT_TTL, blank.defaultTtl());
        assertEquals(CacheProperties.DEFAULT_KEY_PREFIX, blank.keyPrefix());
        // Un TTL de cero es una cache que no cachea nada y paga cada ida y vuelta a Redis.
        assertEquals(CacheProperties.DEFAULT_TTL, new CacheProperties(Duration.ZERO, "p:").defaultTtl());
        assertEquals(CacheProperties.DEFAULT_TTL, new CacheProperties(Duration.ofMinutes(-1), "p:").defaultTtl());
    }

    private static void commit() {
        List.copyOf(TransactionSynchronizationManager.getSynchronizations())
                .forEach(TransactionSynchronization::afterCommit);
    }

    private static CacheInvalidator invalidatorFor(Cache cache) {
        CacheManager cacheManager = mock(CacheManager.class);
        when(cacheManager.getCache(any())).thenReturn(cache);
        return new CacheInvalidator(providerOf(cacheManager));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<CacheManager> providerOf(CacheManager cacheManager) {
        ObjectProvider<CacheManager> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(cacheManager);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<CacheManager> emptyProvider() {
        ObjectProvider<CacheManager> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    @Configuration
    static class CachedProbeConfiguration {

        @Bean
        CachedProbe cachedProbe() {
            return new CachedProbe();
        }
    }

    /** El contador es estatico porque el proxy CGLIB no ejecuta el constructor de la clase base. */
    static class CachedProbe {

        static final AtomicInteger CALLS = new AtomicInteger();

        @Cacheable(cacheNames = CacheNames.MATERIALS, key = "#id")
        public String findById(UUID id) {
            CALLS.incrementAndGet();
            return "value-" + id;
        }
    }
}
