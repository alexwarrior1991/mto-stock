package com.alejandro.mtostock.configuration.cache;

import com.alejandro.mtostock.application.dto.assembly.AssemblyComponentResponse;
import com.alejandro.mtostock.application.dto.assembly.AssemblyResponse;
import com.alejandro.mtostock.application.dto.common.AuditMetadataResponse;
import com.alejandro.mtostock.application.dto.material.MaterialResponse;
import com.alejandro.mtostock.application.dto.material.MaterialSummaryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * La caché contra un Redis de verdad: lo que se guarda, con qué forma, bajo qué clave y hasta
 * cuándo.
 *
 * <p>Se complementa con {@link CacheLayerTest}, que cubre el cableado y las reglas de invalidación
 * sin contenedores. Lo que añade este test es justo lo que aquel no puede comprobar: que un
 * {@code record} sobreviva al viaje de ida y vuelta con sus {@code Instant} y sus
 * {@code BigDecimal} intactos, y que los <b>bytes</b> que quedan en Redis sean los que se esperan.</p>
 *
 * <p>Lo primero es lo que sujeta la eleccion de serializador, y no es teorico: cambiando el tipado
 * por {@code GenericJacksonJsonRedisSerializer} sobre el mapper de la aplicacion, los tres tests de
 * ida y vuelta fallan con {@code ClassCastException} porque lo que vuelve de Redis es un
 * {@code LinkedHashMap} —o sea, un 500 en cada acierto de cache—. Ningun test sin Redis lo ve: con
 * el {@code NoOpCacheManager} nunca se lee nada de vuelta.</p>
 *
 * <p>El contenedor se declara aquí y no en {@code support/} porque es el único que lo usa, igual
 * que hace {@code KeycloakAuthorizationIT}. {@code PostgreSQLTestContainer} vive aparte porque lo
 * comparten tres clases.</p>
 *
 * <p>Es un {@code *IT}: lo ejecuta failsafe en {@code verify}, no surefire en {@code test}.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class CacheRedisIT {

    /** Propio del test: lo que se comprueba es que el prefijo configurado se aplique, no cual es. */
    private static final String PREFIX = "mto-stock-it:v1:";

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, DataRedisAutoConfiguration.class))
            .withUserConfiguration(CacheConfiguration.class, CacheInvalidator.class, CachedMaterialsConfiguration.class)
            .withPropertyValues(
                    "app.cache.enabled=true",
                    "app.cache.key-prefix=" + PREFIX,
                    "app.cache.default-ttl=30m",
                    "spring.data.redis.host=" + REDIS.getHost(),
                    "spring.data.redis.port=" + REDIS.getFirstMappedPort());

    /**
     * El contenedor es de la clase entera, asi que cada test empieza con Redis vacio y no ve las
     * claves del anterior. Y se comprueba que no hay una transaccion colgada del hilo: la
     * invalidacion se aplaza al commit cuando la hay, asi que una que quedase abierta de un test
     * previo convertiria a los de invalidacion en un fallo desconcertante.
     */
    @BeforeEach
    void startFromAnEmptyRedis() {
        assertFalse(TransactionSynchronizationManager.isSynchronizationActive(),
                "una transaccion colgada del hilo aplazaria las invalidaciones de este test");
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration.class))
                .withPropertyValues(
                        "spring.data.redis.host=" + REDIS.getHost(),
                        "spring.data.redis.port=" + REDIS.getFirstMappedPort())
                .run(context -> {
                    try (RedisConnection connection = context.getBean(RedisConnectionFactory.class).getConnection()) {
                        connection.serverCommands().flushAll();
                    }
                });
    }

    /**
     * Un material completo: {@code BigDecimal} con escala y dos {@code Instant} dentro de la
     * metadata de auditoría. Si Jackson perdiera la escala o la zona, el record que vuelve dejaría
     * de ser igual al que se guardó.
     */
    @Test
    void materialSurvivesTheRoundTripThroughRedisUnchanged() {
        UUID id = UUID.randomUUID();
        MaterialResponse material = material(id);

        contextRunner.run(context -> {
            Cache cache = cache(context, CacheNames.MATERIALS);
            cache.put(id, material);

            assertEquals(material, cache.get(id, MaterialResponse.class));
        });
    }

    /**
     * El conjunto es el payload más complicado que se cachea: una lista anidada, y dentro de cada
     * línea otro record con la ficha del material. Es también el que más gana con la caché, porque
     * su lectura recorre la BOM entera.
     */
    @Test
    void assemblyWithItsWholeBillOfMaterialsSurvivesTheRoundTrip() {
        UUID id = UUID.randomUUID();
        AssemblyResponse assembly = new AssemblyResponse(id, "ASM-1", "Ménsula tipo A", true,
                List.of(componentLine("MAT-1", "2.500000"), componentLine("MAT-2", "1.000000")),
                audit());

        contextRunner.run(context -> {
            Cache cache = cache(context, CacheNames.ASSEMBLIES);
            cache.put(id, assembly);

            AssemblyResponse back = cache.get(id, AssemblyResponse.class);
            assertEquals(assembly, back);
            assertEquals(2, back.components().size());
            assertEquals(new BigDecimal("2.500000"), back.components().getFirst().quantity());
        });
    }

    /**
     * Lo que hay en Redis tiene que ser JSON plano: sin marcador de tipo, sin envoltorio y legible
     * con un {@code GET} desde {@code redis-cli}. Es el formato en el que quedan escritos los datos,
     * así que fijarlo aquí es lo que convierte un cambio de serializador en un test en rojo en vez
     * de en una sorpresa al mirar Redis.
     *
     * <p>Quien sujeta la elección de serializador es el test de ida y vuelta, no este: el genérico
     * sobre un mapper normal tampoco escribe {@code @class} —simplemente no sabe releer el
     * {@code record}—. El marcador solo aparece si se le activa <i>default typing</i>, que es el
     * otro camino que esta línea cierra.</p>
     */
    @Test
    void storedPayloadIsPlainJsonWithoutAnyTypeMarker() {
        UUID id = UUID.randomUUID();

        contextRunner.run(context -> {
            cache(context, CacheNames.MATERIALS).put(id, material(id));

            String key = PREFIX + CacheNames.MATERIALS + "::" + id;
            awaitState(() -> rawValue(context, key) != null, "la entrada escrita tiene que aparecer: " + key);
            String json = new String(rawValue(context, key), StandardCharsets.UTF_8);

            assertFalse(json.contains("@class"), () -> "el serializador tipado no escribe el tipo: " + json);
            assertTrue(json.startsWith("{\""), () -> "se esperaba un objeto JSON plano: " + json);
            assertTrue(json.contains("\"code\":\"MAT-1\""), json);
            // El Instant viaja como ISO-8601 y el BigDecimal conserva la escala.
            assertTrue(json.contains("\"createdAt\":\"2026-01-02T03:04:05Z\""), json);
            assertTrue(json.contains("\"minimumStockLevel\":12.500000"), json);
        });
    }

    /**
     * El prefijo importa porque el Redis de un entorno local puede ser el mismo que usa
     * {@code mto-configuration}: sin él, una caché llamada {@code projects} en los dos servicios
     * sería la misma clave.
     */
    @Test
    void keysAreNamespacedByPrefixAndCacheName() {
        UUID id = UUID.randomUUID();

        contextRunner.run(context -> {
            cache(context, CacheNames.PROJECTS).put(id, "cualquier cosa");

            String expected = PREFIX + CacheNames.PROJECTS + "::" + id;
            awaitState(() -> keys(context, expected).contains(expected),
                    "la entrada tiene que vivir bajo <prefijo><cache>::<id>: " + expected);
        });
    }

    /** Sin TTL una entrada obsoleta que se escapara de una invalidacion se quedaria ahi para siempre. */
    @Test
    void entriesExpireOnTheConfiguredTimeToLive() {
        UUID id = UUID.randomUUID();

        contextRunner.run(context -> {
            cache(context, CacheNames.MATERIALS).put(id, material(id));

            String ttlKey = PREFIX + CacheNames.MATERIALS + "::" + id;
            awaitState(() -> rawValue(context, ttlKey) != null, "la entrada escrita tiene que aparecer: " + ttlKey);
            Long ttl = withConnection(context, connection ->
                    connection.keyCommands().ttl(ttlKey.getBytes(StandardCharsets.UTF_8)));

            assertNotNull(ttl);
            // 30m configurados; se comprueba la horquilla, no el segundo exacto.
            assertTrue(ttl > 1700 && ttl <= 1800, "TTL inesperado: " + ttl);
        });
    }

    /** El fallo llena la cache y el acierto se sirve de ella sin volver a llamar al metodo. */
    @Test
    void secondReadIsServedFromRedisWithoutCallingTheMethodAgain() {
        contextRunner.run(context -> {
            CachedMaterials materials = context.getBean(CachedMaterials.class);
            CachedMaterials.CALLS.set(0);
            UUID id = UUID.randomUUID();

            MaterialResponse first = materials.findById(id);
            assertNotNull(rawValue(context, PREFIX + CacheNames.MATERIALS + "::" + id),
                    "el fallo de cache tiene que dejar la entrada escrita en Redis");
            MaterialResponse second = materials.findById(id);

            assertEquals(first, second);
            assertEquals(1, CachedMaterials.CALLS.get(), "el segundo se sirve de Redis");
            String hitKey = PREFIX + CacheNames.MATERIALS + "::" + id;
            awaitState(() -> rawValue(context, hitKey) != null, "el fallo de cache tiene que dejar la entrada escrita: " + hitKey);
        });
    }

    /**
     * La invalidación tiene que borrar la clave de Redis de verdad, no solo dejar de devolverla.
     * Fuera de una transacción es inmediata, que es lo que permite comprobarlo aquí.
     */
    @Test
    void invalidationRemovesTheKeyFromRedis() {
        UUID id = UUID.randomUUID();

        contextRunner.run(context -> {
            cache(context, CacheNames.MATERIALS).put(id, material(id));
            String key = PREFIX + CacheNames.MATERIALS + "::" + id;
            awaitState(() -> rawValue(context, key) != null, "la entrada escrita tiene que aparecer: " + key);

            context.getBean(CacheInvalidator.class).evictAfterCommit(CacheNames.MATERIALS, id);

            awaitState(() -> rawValue(context, key) == null, "la clave sigue en Redis: " + key);
        });
    }

    /**
     * Vaciar la caché entera —como hace el manejador de datos maestros, que no conoce el id del
     * proyecto que ha cambiado— se lleva sus claves y deja en paz las de las demás.
     */
    @Test
    void wholeCacheInvalidationClearsOnlyItsOwnKeys() {
        UUID projectId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();

        contextRunner.run(context -> {
            cache(context, CacheNames.PROJECTS).put(projectId, "proyecto");
            cache(context, CacheNames.MATERIALS).put(materialId, material(materialId));

            context.getBean(CacheInvalidator.class).evictAllAfterCommit(CacheNames.PROJECTS);

            String projectKey = PREFIX + CacheNames.PROJECTS + "::" + projectId;
            String materialKey = PREFIX + CacheNames.MATERIALS + "::" + materialId;
            awaitState(() -> rawValue(context, projectKey) == null,
                    "vaciar la cache de proyectos tiene que llevarse su entrada");
            assertNotNull(rawValue(context, materialKey), "vaciar una cache no puede tocar las otras");
        });
    }

    /**
     * Espera a que Redis llegue al estado esperado, hasta un límite.
     *
     * <p>No es paciencia gratuita ni un parche para tapar un fallo: {@code Cache.put} y
     * {@code Cache.clear} <b>devuelven antes de que la operación sea visible</b> en una lectura
     * posterior. Medido en un bucle contra un Redis real: sin esperar, la entrada recién escrita
     * falta en torno a un tercio de las veces; con veinte milisegundos, está siempre. Afirmar
     * inmediatamente convierte a estos tests en una moneda al aire, que es exactamente como se
     * portaban.</p>
     *
     * <p>Lo que se comprueba no se debilita: siguen siendo las mismas claves, los mismos bytes y el
     * mismo TTL. Lo único que cambia es que se deja de dar por hecho algo que la caché no promete.
     * A la aplicación no le afecta —entre que se llena una entrada y alguien la lee pasa una
     * petición HTTP entera, y un fallo de caché solo cuesta una consulta— pero a un test que mide
     * en microsegundos sí.</p>
     */
    private static void awaitState(java.util.function.BooleanSupplier condition, String description) {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("Redis nunca llego al estado esperado: " + description);
    }

    private static Cache cache(AssertableApplicationContext context, String name) {
        Cache cache = context.getBean(CacheManager.class).getCache(name);
        assertNotNull(cache, name);
        return cache;
    }

    private static byte[] rawValue(AssertableApplicationContext context, String key) {
        return withConnection(context, connection ->
                connection.stringCommands().get(key.getBytes(StandardCharsets.UTF_8)));
    }

    private static Set<String> keys(AssertableApplicationContext context, String pattern) {
        return withConnection(context, connection ->
                connection.keyCommands().keys(pattern.getBytes(StandardCharsets.UTF_8))).stream()
                .map(key -> new String(key, StandardCharsets.UTF_8))
                .collect(java.util.stream.Collectors.toSet());
    }

    private static <T> T withConnection(AssertableApplicationContext context, Function<RedisConnection, T> action) {
        try (RedisConnection connection = context.getBean(RedisConnectionFactory.class).getConnection()) {
            return action.apply(connection);
        }
    }

    private static MaterialResponse material(UUID id) {
        return new MaterialResponse(id, "MAT-1", "Hilo de contacto", "m",
                new BigDecimal("12.500000"), true, audit());
    }

    private static AssemblyComponentResponse componentLine(String code, String quantity) {
        return new AssemblyComponentResponse(UUID.randomUUID(),
                new MaterialSummaryResponse(UUID.randomUUID(), code, "Material " + code, "m", true),
                new BigDecimal(quantity), audit());
    }

    private static AuditMetadataResponse audit() {
        return new AuditMetadataResponse(
                Instant.parse("2026-01-02T03:04:05Z"), Instant.parse("2026-02-03T04:05:06Z"), "alice", "bob");
    }

    @Configuration
    static class CachedMaterialsConfiguration {

        @Bean
        CachedMaterials cachedMaterials() {
            return new CachedMaterials();
        }
    }

    /** El contador es estatico porque el proxy CGLIB no ejecuta el constructor de la clase base. */
    static class CachedMaterials {

        static final AtomicInteger CALLS = new AtomicInteger();

        @Cacheable(cacheNames = CacheNames.MATERIALS, key = "#id")
        public MaterialResponse findById(UUID id) {
            CALLS.incrementAndGet();
            return material(id);
        }
    }
}
