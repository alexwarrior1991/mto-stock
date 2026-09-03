package com.alejandro.mtostock.configuration.cache;

import com.alejandro.mtostock.application.dto.assembly.AssemblyResponse;
import com.alejandro.mtostock.application.dto.material.MaterialResponse;
import com.alejandro.mtostock.application.dto.project.ProjectResponse;
import com.alejandro.mtostock.application.dto.supplier.SupplierResponse;
import com.alejandro.mtostock.application.dto.warehouse.WarehouseResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wires the Redis cache for master data reads.
 *
 * <h2>Por qué está apagada por defecto</h2>
 *
 * <p>{@code matchIfMissing = false}, al revés que en {@code RabbitMqConfiguration}. El canal de
 * mensajería es parte de lo que este servicio hace; la caché es solo una optimización, y el modo
 * seguro por defecto de una optimización es no depender de una pieza más de infraestructura. Con el
 * interruptor apagado no existe ninguno de estos beans, nadie abre una conexión y la aplicación se
 * comporta exactamente igual que antes de que la caché existiera.</p>
 *
 * <h2>Qué se cachea</h2>
 *
 * <p>Solo el catálogo: material, almacén, proveedor, conjunto y proyecto, buscados por id. Son
 * lecturas que se repiten en cada pantalla y en casi todos los flujos de escritura, sobre datos que
 * cambian pocas veces al día. El stock queda fuera a propósito: cambia con cada movimiento y cada
 * reserva, y ya tiene su propia optimización de lectura en {@code inventory_balance}.</p>
 *
 * <h2>Serialización</h2>
 *
 * <p>Un serializador <b>tipado por caché</b> en lugar del genérico. Cada caché guarda exactamente un
 * tipo conocido, así que no hace falta escribir el nombre de la clase dentro del JSON:
 * {@code GenericJacksonJsonRedisSerializer} activa <i>default typing</i> justo para poder
 * reconstruir un tipo que aquí ya se sabe, y eso convierte cualquier cosa escrita en Redis en un
 * vector de deserialización. Con el tipado, lo que queda en Redis es JSON limpio y legible.</p>
 *
 * <p>Se parte del {@link JsonMapper} de la aplicación, el mismo que usa el canal de mensajería, para
 * no mantener dos configuraciones de Jackson que se separan con el tiempo.</p>
 */
@Configuration
@EnableCaching
@EnableConfigurationProperties(CacheProperties.class)
public class CacheConfiguration implements CachingConfigurer {

    private static final Logger LOGGER = LoggerFactory.getLogger(CacheConfiguration.class);

    /**
     * Un tipo por nombre de caché. El mapa es la única lista de la que se fía la configuración: si
     * una caché nueva no aparece aquí, cae en la configuración por defecto y se serializa como
     * {@code Object}, que es lo que hay que evitar.
     */
    private static final Map<String, Class<?>> CACHED_TYPES = cachedTypes();

    private static Map<String, Class<?>> cachedTypes() {
        Map<String, Class<?>> types = new LinkedHashMap<>();
        types.put(CacheNames.MATERIALS, MaterialResponse.class);
        types.put(CacheNames.WAREHOUSES, WarehouseResponse.class);
        types.put(CacheNames.SUPPLIERS, SupplierResponse.class);
        types.put(CacheNames.ASSEMBLIES, AssemblyResponse.class);
        types.put(CacheNames.PROJECTS, ProjectResponse.class);
        return Map.copyOf(types);
    }

    /**
     * La rama con Redis.
     *
     * <p>Va en una clase anidada, y no como condición sobre la de fuera, para que
     * {@code @EnableCaching} se aplique siempre: lo único que cambia entre tener caché y no tenerla
     * es qué {@code CacheManager} hay en el contexto.</p>
     */
    @Configuration
    @ConditionalOnProperty(prefix = "app.cache", name = "enabled", havingValue = "true")
    static class RedisCacheManagerConfiguration {

        @Bean
        RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory,
                                       JsonMapper jsonMapper,
                                       CacheProperties properties) {
            Map<String, RedisCacheConfiguration> configurations = new LinkedHashMap<>();
            CACHED_TYPES.forEach((cacheName, type) ->
                    configurations.put(cacheName, cacheConfiguration(properties, jsonMapper, type)));

            LOGGER.info("Redis cache enabled: caches={}, ttl={}, keyPrefix={}",
                    CacheNames.ALL, properties.defaultTtl(), properties.keyPrefix());

            return RedisCacheManager.builder(connectionFactory)
                    .cacheDefaults(cacheConfiguration(properties, jsonMapper, Object.class))
                    .withInitialCacheConfigurations(configurations)
                    // Un nombre que no este en CACHED_TYPES no se crea sobre la marcha: falla. Sin
                    // esto, una errata en un @Cacheable abre en silencio una cache paralela que
                    // ninguna invalidacion toca nunca, y eso se descubre sirviendo datos viejos.
                    .disableCreateOnMissingCache()
                    .build();
        }

        /**
         * {@code disableCachingNullValues()} encaja con que los {@code findById} nunca devuelven
         * {@code null}: lanzan {@code NotFoundException}. Guardar nulos solo serviría para cachear
         * ausencias que aquí no se producen.
         */
        private static RedisCacheConfiguration cacheConfiguration(CacheProperties properties,
                                                                  JsonMapper jsonMapper,
                                                                  Class<?> type) {
            return RedisCacheConfiguration.defaultCacheConfig()
                    .entryTtl(properties.defaultTtl())
                    .prefixCacheNameWith(properties.keyPrefix())
                    .disableCachingNullValues()
                    .serializeValuesWith(SerializationPair.fromSerializer(
                            new JacksonJsonRedisSerializer<>(jsonMapper, type)));
        }
    }

    /**
     * La rama sin Redis.
     *
     * <p>{@code havingValue = "false"} con {@code matchIfMissing = true} es exactamente «el
     * interruptor está en false o no está», el complemento justo de la otra rama: siempre existe uno
     * y solo uno de los dos {@code CacheManager}.</p>
     *
     * <p>Que sea un {@link NoOpCacheManager} y no la ausencia de caché tiene una ventaja concreta:
     * el interceptor sigue evaluando las expresiones SpEL de cada anotación aunque no guarde nada,
     * así que un {@code key = "#id"} mal escrito revienta en la suite que ya existe —que corre sin
     * Redis— en vez de esperar al primer entorno que la encienda.</p>
     */
    @Configuration
    @ConditionalOnProperty(prefix = "app.cache", name = "enabled", havingValue = "false", matchIfMissing = true)
    static class NoOpCacheManagerConfiguration {

        @Bean
        CacheManager cacheManager() {
            return new NoOpCacheManager();
        }
    }

    /**
     * Registra el manejador de errores a través de {@link CachingConfigurer}.
     *
     * <p>Tiene que ser por aquí y no como un {@code @Bean} suelto: la infraestructura de caché de
     * Spring solo consulta los {@code CachingConfigurer} del contexto, así que un
     * {@code CacheErrorHandler} declarado por su cuenta se crea y no lo usa nadie —con la caché
     * pareciendo resiliente hasta el día que Redis se cae de verdad.</p>
     */
    @Bean
    @Override
    public CacheErrorHandler errorHandler() {
        return new LoggingCacheErrorHandler();
    }
}
