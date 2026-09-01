package com.alejandro.mtostock.application.service.impl;

import com.alejandro.mtostock.application.dto.messaging.MasterDataChangedEvent;
import com.alejandro.mtostock.application.dto.messaging.MasterDataChangedMessage;
import com.alejandro.mtostock.application.service.MasterDataEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;

/**
 * Placeholder handler: records the event and does nothing else.
 *
 * <p>No escribe en base de datos ni llama a ningún servicio de stock, y es intencionado. Mientras
 * no haya reglas acordadas sobre qué hace {@code mto-stock} cuando cambia un dato maestro, un
 * manejador que solo registra deja el canal montado y observable sin comprometer ninguna decisión
 * de negocio.</p>
 *
 * <p>Sustituirlo es borrar esta clase y poner otra: el consumidor depende de la interfaz, no de
 * esta implementación. No se usa {@code @ConditionalOnMissingBean} para que convivan dos, porque
 * fuera de una autoconfiguración esa anotación depende del orden en que se registren las
 * definiciones de bean, y el resultado sería que a veces se ejecuta el manejador que no toca.</p>
 *
 * <h2>Qué se registra y en qué nivel</h2>
 *
 * <p>Los metadatos van a INFO y el contenido a DEBUG. El mapa {@code values} lo compone
 * {@code mto-configuration} y este servicio no tiene forma de saber qué hay dentro; publicar sus
 * valores a INFO sería decidir por adelantado que nada de lo que publique el emisor —ni ahora ni
 * cuando añada entidades— es sensible. Los nombres de los campos sí van a INFO: son suficientes
 * para ver qué está llegando y no son el dato.</p>
 */
@Service
class LoggingMasterDataEventHandler implements MasterDataEventHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(LoggingMasterDataEventHandler.class);

    @Override
    public void handle(MasterDataChangedMessage message) {
        MasterDataChangedEvent event = message.data();

        LOGGER.info("Master data change received: eventType={}, entity={}, entityId={}, operation={}, "
                        + "operationId={}, origin={}, createdAt={}, fields={}",
                message.eventType(),
                event.entityName(),
                event.entityId(),
                event.operation(),
                message.operationId(),
                message.origin(),
                message.creationDate(),
                fieldNames(event.values()));

        // TODO: implementar aquí la lógica de negocio, o sustituir este bean por otra
        //  implementación de MasterDataEventHandler. Ver el javadoc de la interfaz para lo que hay
        //  que resolver antes de escribir en base de datos: idempotencia, orden y firma.
        LOGGER.debug("Master data change payload: operationId={}, referenceId={}, values={}",
                message.operationId(), message.referenceId(), event.values());
    }

    private static Set<String> fieldNames(Map<String, Object> values) {
        return values == null ? Set.of() : values.keySet();
    }
}
