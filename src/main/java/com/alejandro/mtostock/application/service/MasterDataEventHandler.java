package com.alejandro.mtostock.application.service;

import com.alejandro.mtostock.application.dto.messaging.MasterDataChangedMessage;

/**
 * Single entry point for master data changes published by {@code mto-configuration}.
 *
 * <p>Hay una sola implementación y no se espera que haya más:
 * {@code DispatchingMasterDataEventHandler}, que mira qué entidad cambió y se lo pasa al
 * {@link MasterDataEntityHandler} que la atienda. Esta interfaz es la frontera entre el transporte
 * y la aplicación —lo que el inbox ejecuta exactamente una vez—, no el sitio donde se escribe la
 * lógica.</p>
 *
 * <p><b>La lógica de negocio va en {@link MasterDataEntityHandler}</b>, uno por tipo de entidad.
 * Implementar esta interfaz en su lugar sustituiría al despachador y dejaría sin ejecutar todos los
 * manejadores por entidad que hubiera registrados.</p>
 *
 * <p>Sobre las garantías: lo que se ejecute desde aquí corre dentro de la transacción del inbox y
 * se aplica exactamente una vez por mensaje, así que no hay que comprobar repeticiones. Lo que se
 * lance viaja hasta el contenedor de listeners, que lo reintenta y acaba mandando el mensaje a la
 * DLQ; el inbox deja la fila en {@code FAILED} con el motivo.</p>
 */
public interface MasterDataEventHandler {

    /**
     * Procesa un cambio de datos maestros ya deserializado.
     *
     * @param message sobre completo, nunca {@code null}, con su payload de negocio en
     *                {@code data()}, y con {@code entityName} y {@code operation} ya validados por
     *                el consumidor
     */
    void handle(MasterDataChangedMessage message);
}
