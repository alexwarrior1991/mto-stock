package com.alejandro.mtostock.configuration.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;

/**
 * Lets any Redis failure fall through to the database instead of propagating it.
 *
 * <p>Sin esto, el comportamiento por defecto de Spring es que la excepción suba hasta el
 * controlador: un Redis caído dejaría de ser una caché fría para convertirse en una caída del
 * servicio, que es exactamente lo contrario de lo que se busca al añadir una caché. Con esto, un
 * Redis que no responde degrada la aplicación a lo que era antes de tenerlo.</p>
 *
 * <p>De los cuatro fallos, tragarse el de <b>evicción</b> es el único que deja datos rancios en
 * Redis. Se acepta: el TTL acaba cerrando la ventana, y la alternativa —hacer fallar una escritura
 * que Postgres ya ha confirmado— es peor que servir un nombre viejo un rato.</p>
 *
 * <p>Se registra en WARN y no en ERROR: la petición se ha atendido correctamente. Lo que hay que
 * vigilar es que estas líneas aparezcan de forma sostenida, no una suelta durante un reinicio de
 * Redis.</p>
 */
class LoggingCacheErrorHandler implements CacheErrorHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(LoggingCacheErrorHandler.class);

    @Override
    public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
        log("read", cache, key, exception);
    }

    @Override
    public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
        log("write", cache, key, exception);
    }

    @Override
    public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
        log("eviction", cache, key, exception);
    }

    @Override
    public void handleCacheClearError(RuntimeException exception, Cache cache) {
        log("clear", cache, null, exception);
    }

    private void log(String operation, Cache cache, Object key, RuntimeException exception) {
        LOGGER.warn("Cache {} failed, falling back to the database: cache={}, key={}, cause={}",
                operation, cache.getName(), key, exception.toString());
    }
}
