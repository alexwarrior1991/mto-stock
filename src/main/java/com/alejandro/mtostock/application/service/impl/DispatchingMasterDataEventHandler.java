package com.alejandro.mtostock.application.service.impl;

import com.alejandro.mtostock.application.dto.messaging.MasterDataChangedEvent;
import com.alejandro.mtostock.application.dto.messaging.MasterDataChangedMessage;
import com.alejandro.mtostock.application.service.MasterDataEntityHandler;
import com.alejandro.mtostock.application.service.MasterDataEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Routes each master data change to the handler of its entity, if there is one.
 *
 * <p>Es el único {@link MasterDataEventHandler} de la aplicación y no contiene lógica de negocio:
 * mira qué entidad cambió, busca quién la atiende y le pasa el mensaje. La lógica vive en las
 * implementaciones de {@link MasterDataEntityHandler}, una por tipo de entidad, que se registran
 * solas por estar en el contexto.</p>
 *
 * <h2>Lo que no se reconoce se ignora, no falla</h2>
 *
 * <p>{@code mto-configuration} publica hoy ocho tipos de entidad —paquetes de ejecución,
 * estaciones, vías, perfiles, ménsulas, brazos, seccionadores y aisladores— y puede añadir más sin
 * avisar a nadie: la cola de este servicio está enlazada a {@code mto.master-data.#}, así que llega
 * todo. Tratar como error lo que no se atiende mandaría a la DLQ la mayor parte del tráfico normal
 * y convertiría cada entidad nueva del emisor en una avería aquí.</p>
 *
 * <p>Un evento ignorado se marca igualmente como aplicado en el inbox, y es lo correcto: ya se
 * decidió qué hacer con él —nada—, y volver a entregarlo no cambiaría esa decisión. Lo que no se
 * pierde es el rastro: la fila del inbox conserva el payload original, de modo que un manejador
 * escrito más adelante puede reprocesar lo que quedó guardado.</p>
 */
@Service
class DispatchingMasterDataEventHandler implements MasterDataEventHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(DispatchingMasterDataEventHandler.class);

    private final Map<String, MasterDataEntityHandler> handlersByEntityName;

    DispatchingMasterDataEventHandler(List<MasterDataEntityHandler> entityHandlers) {
        this.handlersByEntityName = indexByEntityName(entityHandlers);

        LOGGER.info("Master data dispatcher ready: {} entity handler(s) registered for {}",
                handlersByEntityName.size(), handlersByEntityName.keySet());
    }

    @Override
    public void handle(MasterDataChangedMessage message) {
        MasterDataChangedEvent event = message.data();
        String entityName = normalize(event.entityName());

        LOGGER.info("Master data change received: eventType={}, entity={}, entityId={}, operation={}, "
                        + "operationId={}, origin={}, createdAt={}, fields={}",
                message.eventType(), event.entityName(), event.entityId(), event.operation(),
                message.operationId(), message.origin(), message.creationDate(), fieldNames(event));

        // El contenido va a DEBUG: el mapa lo compone mto-configuration y este servicio no sabe qué
        // hay dentro, así que publicar sus valores a INFO sería decidir por adelantado que nada de
        // lo que envíe el emisor —ni ahora ni cuando añada entidades— es sensible.
        LOGGER.debug("Master data change payload: operationId={}, referenceId={}, values={}",
                message.operationId(), message.referenceId(), event.values());

        MasterDataEntityHandler handler = handlersByEntityName.get(entityName);

        if (handler == null) {
            LOGGER.info("No handler registered for entity '{}', change ignored: eventType={}, entityId={}. "
                            + "The message stays recorded in the inbox with its original payload.",
                    event.entityName(), message.eventType(), event.entityId());
            return;
        }

        dispatch(handler, message, event);

        LOGGER.debug("Master data change dispatched to {}: entity={}, operation={}",
                handler.getClass().getSimpleName(), entityName, event.operation());
    }

    private static void dispatch(
            MasterDataEntityHandler handler,
            MasterDataChangedMessage message,
            MasterDataChangedEvent event) {

        switch (event.operation()) {
            case CREATED -> handler.onCreated(message);
            case UPDATED -> handler.onUpdated(message);
            case DELETED -> handler.onDeleted(message);
        }
    }

    /**
     * Dos manejadores para la misma entidad no se pueden resolver: cuál ganara dependería del orden
     * de escaneo del classpath, y eso es una diferencia de comportamiento entre dos arranques del
     * mismo binario. Mejor no arrancar.
     */
    private static Map<String, MasterDataEntityHandler> indexByEntityName(
            List<MasterDataEntityHandler> entityHandlers) {

        Map<String, MasterDataEntityHandler> index = new LinkedHashMap<>();

        for (MasterDataEntityHandler handler : entityHandlers) {
            String entityName = normalize(handler.entityName());

            if (entityName.isEmpty()) {
                throw new IllegalStateException(
                        handler.getClass().getName() + " returns a blank entityName");
            }

            MasterDataEntityHandler previous = index.put(entityName, handler);

            if (previous != null) {
                throw new IllegalStateException(("Two handlers claim the master data entity '%s': %s and %s. "
                        + "Which one ran would depend on the classpath scanning order.")
                        .formatted(entityName, previous.getClass().getName(), handler.getClass().getName()));
            }
        }

        return Map.copyOf(index);
    }

    /**
     * El emisor ya normaliza los nombres a minúsculas con guiones, pero se vuelve a normalizar aquí:
     * un manejador que declarase {@code "Execution-Package"} no llegaría a ejecutarse nunca y nada
     * lo delataría.
     */
    private static String normalize(String entityName) {
        return entityName == null ? "" : entityName.trim().toLowerCase();
    }

    /** Los nombres de los campos sí van a INFO: bastan para ver qué llega y no son el dato. */
    private static Set<String> fieldNames(MasterDataChangedEvent event) {
        return event.values() == null ? Set.of() : new TreeSet<>(event.values().keySet());
    }
}
