package com.alejandro.mtostock.application.service;

import com.alejandro.mtostock.application.dto.messaging.MasterDataChangedMessage;

/**
 * Application entry point for master data changes published by {@code mto-configuration}.
 *
 * <h2>Aquí es donde va la lógica de negocio</h2>
 *
 * <p>TODO: este es el punto de extensión. Hoy la única implementación
 * ({@code LoggingMasterDataEventHandler}) se limita a registrar el evento. Cuando toque reaccionar
 * de verdad —dar de alta el material que llega de {@code mto-configuration}, propagar el cambio de
 * un almacén, invalidar una caché— la implementación nueva va en
 * {@code application/service/impl}, con su {@code @Transactional}, y el consumidor de infraestructura
 * no se toca.</p>
 *
 * <p>Cuando llegue ese momento hay tres cosas del transporte que la implementación tendrá que
 * decidir, y que hoy no están resueltas porque no hay nada que proteger todavía:</p>
 *
 * <ul>
 *   <li><b>Idempotencia.</b> La entrega es <i>at-least-once</i>: el mismo mensaje puede llegar dos
 *       veces. {@code operationId} identifica la operación de origen y sirve para descartar el
 *       repetido.</li>
 *   <li><b>Orden.</b> Un redrive puede reentregar algo antiguo. La cabecera
 *       {@code sequenceNumber} permite descartar lo anterior a lo ya aplicado para ese agregado.</li>
 *   <li><b>Integridad.</b> La cabecera {@code messageSignature} firma los bytes recibidos y es
 *       comprobable; el {@code messageHash} del payload no lo es. Verificarla exige compartir
 *       {@code app.messaging.signature.secret} con {@code mto-configuration}.</li>
 * </ul>
 *
 * <p>Sobre los errores: lo que esta interfaz lance viaja hasta el contenedor de listeners, que lo
 * reintenta y acaba mandando el mensaje a la DLQ. Un fallo transitorio (la base de datos no
 * responde) debe propagarse tal cual para que se reintente; un mensaje que nunca se va a poder
 * procesar debe lanzar {@code AmqpRejectAndDontRequeueException} para ir directo a la DLQ sin
 * gastar reintentos.</p>
 */
public interface MasterDataEventHandler {

    /**
     * Procesa un cambio de datos maestros ya deserializado.
     *
     * @param message sobre completo, nunca {@code null}, con su payload de negocio en
     *                {@code data()}
     */
    void handle(MasterDataChangedMessage message);
}
