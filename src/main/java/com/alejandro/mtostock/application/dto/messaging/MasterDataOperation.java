package com.alejandro.mtostock.application.dto.messaging;

/**
 * Operation that triggered a master data change in {@code mto-configuration}.
 *
 * <p>Los nombres son los del emisor y viajan tal cual dentro del payload, así que renombrar una
 * constante rompe la deserialización de los mensajes que ya están en la cola. El valor de
 * enrutado —la parte que aparece en la routing key— sí es distinto: va en minúsculas.</p>
 */
public enum MasterDataOperation {

    CREATED("created"),
    UPDATED("updated"),
    DELETED("deleted");

    private final String routingValue;

    MasterDataOperation(String routingValue) {
        this.routingValue = routingValue;
    }

    /** Fragmento que ocupa esta operación en la routing key {@code mto.master-data.<entidad>.<op>}. */
    public String routingValue() {
        return routingValue;
    }
}
