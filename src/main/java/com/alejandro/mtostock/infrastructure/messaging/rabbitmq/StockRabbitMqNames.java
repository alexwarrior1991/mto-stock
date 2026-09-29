package com.alejandro.mtostock.infrastructure.messaging.rabbitmq;

import java.util.Locale;

/**
 * Nombres del exchange propio de este servicio y de lo que se enruta por el.
 *
 * <p>Es un exchange distinto de {@code mto.master-data.exchange} a proposito: aquel es el contrato
 * de los datos maestros de {@code mto-configuration}, y lo que este servicio cuenta de si mismo (un
 * material que cae por debajo de su minimo, una reserva cancelada o liberada, un ajuste de
 * inventario) sale por aqui. Solo se declara el exchange: la cola es de quien la consume
 * ({@code mto-notification}), que la bindea con {@code mto.stock.#} o con lo que le interese.</p>
 *
 * <p>La clave de enrutado y el {@code eventType} son contrato: el consumidor deriva de la clave el
 * tipo de actividad ({@code stock.<entidad>.<evento>}), asi que el formato no es cosmetico.</p>
 */
public final class StockRabbitMqNames {

    public static final String STOCK_EXCHANGE = "mto.stock.exchange";

    public static final String STOCK_ROUTING_PREFIX = "mto.stock";
    public static final String STOCK_ROUTING_PATTERN = "mto.stock.#";

    private static final String EVENT_TYPE_PREFIX = "STOCK";

    private StockRabbitMqNames() {
    }

    /** {@code mto.stock.<entidad>.<evento>}: lo que decide a que colas llega el mensaje. */
    public static String routingKey(String entityName, String eventName) {
        return STOCK_ROUTING_PREFIX + "." + normalize(entityName) + "." + normalize(eventName);
    }

    /** {@code STOCK_<ENTIDAD>_<EVENTO>}: el {@code eventType} del sobre y de la cabecera. */
    public static String eventType(String entityName, String eventName) {
        return EVENT_TYPE_PREFIX + "_" + constant(entityName) + "_" + constant(eventName);
    }

    private static String normalize(String value) {
        return value.trim()
                .toLowerCase(Locale.ROOT)
                .replace("_", "-")
                .replace(" ", "-");
    }

    private static String constant(String value) {
        return normalize(value)
                .toUpperCase(Locale.ROOT)
                .replace("-", "_");
    }
}
