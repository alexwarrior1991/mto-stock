package com.alejandro.mtostock.infrastructure.messaging.rabbitmq;

/**
 * Bean names of the listener container factories of this service.
 *
 * <p>El nombre viaja como cadena dentro de {@code @RabbitListener}, donde ningún compilador lo
 * comprueba: si el bean se renombra y la anotación no, Spring falla al arrancar buscando una
 * factory que ya no existe. Con la constante, el renombrado arrastra los dos sitios.</p>
 */
public final class RabbitListenerContainerFactoryNames {

    /** Factory del canal de datos maestros. */
    public static final String MASTER_DATA = "masterDataRabbitListenerContainerFactory";

    private RabbitListenerContainerFactoryNames() {
    }
}
