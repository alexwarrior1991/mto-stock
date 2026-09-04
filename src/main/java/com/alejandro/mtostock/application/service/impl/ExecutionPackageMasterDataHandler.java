package com.alejandro.mtostock.application.service.impl;

import com.alejandro.mtostock.application.dto.messaging.MasterDataChangedEvent;
import com.alejandro.mtostock.application.dto.messaging.MasterDataChangedMessage;
import com.alejandro.mtostock.application.dto.messaging.MasterDataEntityNames;
import com.alejandro.mtostock.application.dto.messaging.MasterDataEventContext;
import com.alejandro.mtostock.application.exception.ValidationException;
import com.alejandro.mtostock.application.service.MasterDataEntityHandler;
import com.alejandro.mtostock.configuration.cache.CacheInvalidator;
import com.alejandro.mtostock.configuration.cache.CacheNames;
import com.alejandro.mtostock.infrastructure.persistence.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Keeps a {@code project} in step with the execution package it comes from.
 *
 * <p>Un paquete de ejecución es la unidad de obra en {@code mto-configuration}, y un proyecto es
 * aquello contra lo que se reservan y se consumen materiales aquí: es la única de las ocho
 * entidades que publica el emisor con una correspondencia real en este dominio. Las otras siete
 * —estaciones, vías, perfiles, ménsulas, brazos, seccionadores, aisladores— describen la geometría
 * de la catenaria y no tienen equivalente en un almacén.</p>
 *
 * <h2>Qué se copia y qué no</h2>
 *
 * <p>Solo el nombre y si está activo, que es todo lo que un proyecto de {@code mto-stock} sabe
 * guardar. Las fechas, la longitud, la empresa, las vías y las estaciones del paquete viajan en el
 * evento y se descartan: darles columnas aquí sería duplicar el modelo de {@code mto-configuration}
 * en un servicio que no lo usa para nada. Si algún día hacen falta, están en el payload que el
 * inbox conserva.</p>
 *
 * <h2>Orden</h2>
 *
 * <p>El inbox impide aplicar dos veces el mismo mensaje, no aplicar uno viejo después de uno nuevo.
 * Si un evento falla y se reprograma, el siguiente del mismo paquete puede adelantarlo, y sin
 * ninguna protección el nombre viejo pisaría al nuevo — o un {@code UPDATE} retrasado detrás de un
 * {@code DELETE} reactivaría el proyecto. La marca de agua {@code project.source_sequence_number}
 * descarta lo que venga por debajo de lo ya aplicado, y la comprobación vive dentro de la propia
 * sentencia de escritura: entre una lectura y una escritura caben dos entregas.</p>
 *
 * <p>Un evento sin número de secuencia se aplica igualmente. No se puede saber que sea viejo, y
 * rechazarlo dejaría el servicio sin consumir nada si el emisor dejara de enviar la cabecera.</p>
 *
 * <h2>Caché</h2>
 *
 * <p>Este es el segundo camino por el que cambia un {@code project}: el primero es
 * {@code ProjectService}, que invalida su propia entrada. Aquí se escribe con SQL nativo sin pasar
 * por ese servicio, así que sin invalidar a mano un proyecto renombrado o dado de baja desde
 * {@code mto-configuration} se seguiría sirviendo viejo desde la caché hasta que expirase el TTL.</p>
 *
 * <p>Se tira la caché <b>entera</b> y no una entrada: las sentencias de {@code ProjectRepository}
 * devuelven un contador de filas, no el proyecto tocado, así que no hay clave que invalidar. Son
 * eventos poco frecuentes y el coste es un puñado de fallos de caché.</p>
 *
 * <p>Y se hace después del <i>commit</i> de la transacción del inbox, no al vuelo: si el mensaje
 * acaba fallando y la transacción se deshace, una invalidación inmediata ya habría tirado la caché
 * por un cambio que no se llegó a aplicar.</p>
 *
 * <h2>Identidad</h2>
 *
 * <p>El proyecto se reconoce por {@code (source_service, source_entity_id)} y no por su código. El
 * código se deriva del identificador de origen —{@code EP-42}— porque {@code project.code} es
 * obligatorio y único, y el paquete de ejecución no publica ninguno; derivarlo lo hace estable
 * entre entregas en lugar de depender del nombre, que sí cambia.</p>
 */
@Service
@RequiredArgsConstructor
class ExecutionPackageMasterDataHandler implements MasterDataEntityHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExecutionPackageMasterDataHandler.class);

    /** Servicio de origen que se registra en el proyecto; coincide con el {@code origin} del sobre. */
    static final String SOURCE_SERVICE = "mto-configuration";

    static final String PROJECT_CODE_PREFIX = "EP-";

    private static final int MAX_NAME_LENGTH = 255;
    private static final int MAX_SOURCE_ENTITY_ID_LENGTH = 100;

    private final ProjectRepository projectRepository;
    private final CacheInvalidator cacheInvalidator;

    @Override
    public String entityName() {
        return MasterDataEntityNames.EXECUTION_PACKAGE;
    }

    @Override
    public void onCreated(MasterDataChangedMessage message, MasterDataEventContext context) {
        synchronizeProject(message, context);
    }

    /**
     * El alta y la modificación hacen lo mismo a propósito. La entrega es <i>at-least-once</i> y el
     * orden entre agregados distintos no está garantizado, así que tratar el alta como un
     * «insertar» que falla si ya existe convertiría en avería una reentrega perfectamente normal.
     */
    @Override
    public void onUpdated(MasterDataChangedMessage message, MasterDataEventContext context) {
        synchronizeProject(message, context);
    }

    @Override
    public void onDeleted(MasterDataChangedMessage message, MasterDataEventContext context) {
        String sourceEntityId = sourceEntityId(message.data());

        int deactivated = projectRepository.deactivateFromMasterData(
                SOURCE_SERVICE, sourceEntityId, context.sequenceNumber());

        if (deactivated == 0) {
            logNothingDeactivated(sourceEntityId, context);
            return;
        }

        LOGGER.info("Project deactivated after its execution package was deleted: sourceEntityId={}, code={}, "
                        + "sequenceNumber={}",
                sourceEntityId, projectCode(sourceEntityId), context.sequenceNumber());
        cacheInvalidator.evictAllAfterCommit(CacheNames.PROJECTS);
    }

    private void synchronizeProject(MasterDataChangedMessage message, MasterDataEventContext context) {
        MasterDataChangedEvent event = message.data();
        String sourceEntityId = sourceEntityId(event);
        String code = projectCode(sourceEntityId);
        String name = name(event, sourceEntityId);
        boolean active = active(event);
        int applied;

        try {
            applied = projectRepository.upsertFromMasterData(
                    SOURCE_SERVICE, sourceEntityId, code, name, active, context.sequenceNumber());
        } catch (DataIntegrityViolationException exception) {
            // El choque realista es con uq_project_code: alguien creó a mano un proyecto con este
            // mismo código. Sin este mensaje, lo que se lee en la DLQ es una violación de
            // restricción sin contexto y cuesta una tarde averiguar de dónde salía.
            throw new ValidationException(("Project '%s' cannot be synchronized from execution package %s: "
                    + "another project already uses that code, or the source columns collide. "
                    + "Rename or remove the conflicting project.").formatted(code, sourceEntityId));
        }

        if (applied == 0) {
            // La sentencia no toca la fila cuando el evento viene por detrás de lo ya aplicado. No es
            // un fallo: es exactamente lo que tiene que pasar con un cambio que llega tarde.
            LOGGER.info("Execution package change discarded, a newer one was already applied: "
                            + "sourceEntityId={}, code={}, sequenceNumber={}",
                    sourceEntityId, code, context.sequenceNumber());
            return;
        }

        LOGGER.info("Project synchronized from execution package: sourceEntityId={}, code={}, active={}, "
                        + "sequenceNumber={}",
                sourceEntityId, code, active, context.sequenceNumber());
        cacheInvalidator.evictAllAfterCommit(CacheNames.PROJECTS);
    }

    /**
     * Las dos razones por las que no se desactivó nada son normales, pero muy distintas de leer a las
     * tres de la mañana: una dice que este servicio nunca supo del paquete, y la otra que la baja
     * llegó tarde. Cuesta una consulta más, y solo en el camino en el que no se hizo nada.
     */
    private void logNothingDeactivated(String sourceEntityId, MasterDataEventContext context) {
        boolean known = projectRepository
                .findBySourceServiceAndSourceEntityId(SOURCE_SERVICE, sourceEntityId)
                .isPresent();

        if (known) {
            LOGGER.info("Execution package deletion discarded, a newer change was already applied: "
                            + "sourceEntityId={}, sequenceNumber={}",
                    sourceEntityId, context.sequenceNumber());
        } else {
            LOGGER.info("Execution package deleted but no project came from it, nothing to do: "
                    + "sourceEntityId={}", sourceEntityId);
        }
    }

    /**
     * El identificador de origen sale de {@code entityId}, que el emisor rellena siempre, y no de
     * {@code values.id}: el primero es parte del sobre y el segundo depende de que el mapper del
     * emisor siga publicando ese campo.
     */
    private static String sourceEntityId(MasterDataChangedEvent event) {
        String entityId = event.entityId();

        if (entityId == null || entityId.isBlank()) {
            throw new ValidationException(
                    "Execution package event has no entityId, so there is no project to synchronize");
        }
        if (entityId.length() > MAX_SOURCE_ENTITY_ID_LENGTH) {
            throw new ValidationException(
                    "Execution package entityId is longer than " + MAX_SOURCE_ENTITY_ID_LENGTH + " characters");
        }

        return entityId.trim();
    }

    private static String projectCode(String sourceEntityId) {
        return PROJECT_CODE_PREFIX + sourceEntityId;
    }

    /**
     * El nombre es obligatorio en origen y en destino, así que su ausencia es un mensaje que no
     * cuadra con el contrato y no algo de lo que se pueda salir poniendo un valor cualquiera: un
     * proyecto llamado «EP-42» en la pantalla de reservas no lo reconoce nadie.
     */
    private static String name(MasterDataChangedEvent event, String sourceEntityId) {
        Object rawName = value(event, "name");

        if (!(rawName instanceof String name) || name.isBlank()) {
            throw new ValidationException(
                    "Execution package " + sourceEntityId + " has no name, so the project cannot be named");
        }

        String trimmed = name.trim();

        return trimmed.length() <= MAX_NAME_LENGTH ? trimmed : trimmed.substring(0, MAX_NAME_LENGTH);
    }

    /**
     * Un paquete sin {@code enabled} se toma por activo, que es el valor por defecto en origen.
     * Suponer lo contrario desactivaría proyectos vivos por un campo que faltase.
     */
    private static boolean active(MasterDataChangedEvent event) {
        Object enabled = value(event, "enabled");

        if (enabled == null) {
            return true;
        }
        if (enabled instanceof Boolean flag) {
            return flag;
        }

        return Boolean.parseBoolean(enabled.toString());
    }

    private static Object value(MasterDataChangedEvent event, String field) {
        Map<String, Object> values = event.values();
        return values == null ? null : values.get(field);
    }
}
