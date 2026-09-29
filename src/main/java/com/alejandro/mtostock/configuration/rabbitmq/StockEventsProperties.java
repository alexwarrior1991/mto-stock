package com.alejandro.mtostock.configuration.rabbitmq;

import com.alejandro.mtostock.infrastructure.messaging.rabbitmq.StockRabbitMqNames;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Name of the exchange this service publishes its own events to, configurable per environment.
 *
 * <p>Como {@link MasterDataRabbitProperties}: el valor por defecto es el contrato
 * ({@code mto.stock.exchange}), y una cadena en blanco cuenta como no configurada en vez de
 * arrancar contra un exchange llamado {@code ""}. Solo el exchange: las claves de enrutado salen de
 * {@link StockRabbitMqNames} y la cola es de quien la consume.</p>
 */
@Validated
@ConfigurationProperties(prefix = "app.rabbitmq.events")
public record StockEventsProperties(@NotBlank String exchange) {

    public StockEventsProperties {
        exchange = exchange == null || exchange.isBlank() ? StockRabbitMqNames.STOCK_EXCHANGE : exchange;
    }
}
