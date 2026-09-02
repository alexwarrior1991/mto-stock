package com.alejandro.mtostock.application.service;

import com.alejandro.mtostock.application.dto.messaging.MasterDataChangedMessage;
import com.alejandro.mtostock.application.dto.messaging.MasterDataEventContext;

/**
 * Reacts to changes of one kind of master data entity.
 *
 * <h2>Este es el punto donde se implementa la lógica de negocio</h2>
 *
 * <p>Para que {@code mto-stock} haga algo cuando cambie, por ejemplo, un paquete de ejecución,
 * basta con crear un {@code @Service} que implemente esta interfaz, devuelva
 * {@code MasterDataEntityNames.EXECUTION_PACKAGE} en {@link #entityName()} y sobrescriba las
 * operaciones que le interesen. El despachador lo encuentra solo: no hay que registrarlo en ningún
 * sitio ni tocar el consumidor de RabbitMQ.</p>
 *
 * <p>Todo lo que se haga aquí corre <b>dentro de la transacción del inbox</b>, junto al registro del
 * mensaje: o se confirma todo o no se confirma nada, y el mensaje se aplica exactamente una vez por
 * mucho que el broker lo entregue repetido. No hace falta comprobar repeticiones. Lo que sí hay que
 * respetar es esa transacción: abrir otra por debajo —un {@code REQUIRES_NEW}, otro datasource, una
 * llamada saliente— rompe esa garantía y deja el problema de la aplicación parcial en manos de
 * quien lo escriba.</p>
 *
 * <p>Lo que se lance viaja hasta el contenedor de listeners: un fallo transitorio se propaga tal
 * cual para que se reintente, y el inbox deja la fila en {@code FAILED} con el motivo.</p>
 *
 * <h2>Sobre los valores del evento</h2>
 *
 * <p>{@code message.data().values()} es un mapa abierto que compone el emisor, con la forma que le
 * da su propio {@code MasterDataEntityPayloadMapper}. Traducirlo a tipos de este dominio es trabajo
 * de la implementación, y conviene hacerlo a la defensiva: un campo que hoy está puede desaparecer
 * en el siguiente despliegue de {@code mto-configuration} sin que este servicio se entere.</p>
 */
public interface MasterDataEntityHandler {

    /**
     * Nombre lógico de la entidad que atiende, tal y como viaja en el evento.
     *
     * <p>Los valores válidos están en {@code MasterDataEntityNames}. Dos manejadores para la misma
     * entidad hacen que la aplicación no arranque: cuál de los dos se ejecutase dependería del
     * orden de escaneo del classpath.</p>
     */
    String entityName();

    /**
     * Los tres métodos no hacen nada por defecto a propósito: atender solo una operación —reaccionar
     * a las bajas y no a las altas, por ejemplo— es una decisión frecuente y legítima, y obligar a
     * escribir dos cuerpos vacíos para expresarla solo añade ruido.
     */
    default void onCreated(MasterDataChangedMessage message, MasterDataEventContext context) {
        // Sin reacción por defecto.
    }

    default void onUpdated(MasterDataChangedMessage message, MasterDataEventContext context) {
        // Sin reacción por defecto.
    }

    default void onDeleted(MasterDataChangedMessage message, MasterDataEventContext context) {
        // Sin reacción por defecto.
    }
}
