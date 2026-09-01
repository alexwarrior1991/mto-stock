package com.alejandro.mtostock.configuration.rabbitmq;

import com.alejandro.mtostock.infrastructure.messaging.rabbitmq.MasterDataRabbitMqNames;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Names of the master data topology, configurable per environment.
 *
 * <p>Todos los valores traen por defecto el contrato real de {@code mto-configuration}, de modo que
 * un entorno que no configure nada queda ya enganchado al canal correcto. Se dejan en properties
 * porque los nombres del broker sí cambian entre instalaciones (un vhost compartido, una prueba
 * contra una cola aparte), y ahí un valor en el YAML es preferible a recompilar.</p>
 *
 * <p>El interruptor del consumidor <b>no</b> está aquí: {@code app.rabbitmq.master-data.listener-enabled}
 * lo lee el {@code @ConditionalOnProperty} de {@link RabbitMqConfiguration}, que es quien decide si
 * el bean existe. Tenerlo además como campo obligaría a mantener dos lecturas del mismo
 * interruptor, y la que no manda es justo la que acaba mintiendo.</p>
 *
 * <p>Las cadenas en blanco se sustituyen por el valor por defecto en lugar de rechazarse: una
 * variable de entorno declarada y vacía es el caso normal de un despliegue a medio configurar, y
 * arrancar contra un exchange llamado {@code ""} sería peor que arrancar contra el contrato.</p>
 */
@Validated
@ConfigurationProperties(prefix = "app.rabbitmq.master-data")
public record MasterDataRabbitProperties(
        @NotBlank String exchange,
        @NotBlank String queue,
        @NotBlank String routingKey,
        @NotBlank String deadLetterExchange,
        @NotBlank String deadLetterQueue,
        @NotBlank String deadLetterRoutingKey
) {

    public MasterDataRabbitProperties {
        exchange = defaultIfBlank(exchange, MasterDataRabbitMqNames.MASTER_DATA_EXCHANGE);
        queue = defaultIfBlank(queue, MasterDataRabbitMqNames.STOCK_MASTER_DATA_QUEUE);
        routingKey = defaultIfBlank(routingKey, MasterDataRabbitMqNames.MASTER_DATA_ROUTING_PATTERN);
        deadLetterExchange = defaultIfBlank(
                deadLetterExchange, MasterDataRabbitMqNames.STOCK_MASTER_DATA_DEAD_LETTER_EXCHANGE);
        deadLetterQueue = defaultIfBlank(
                deadLetterQueue, MasterDataRabbitMqNames.STOCK_MASTER_DATA_DEAD_LETTER_QUEUE);
        deadLetterRoutingKey = defaultIfBlank(
                deadLetterRoutingKey, MasterDataRabbitMqNames.STOCK_MASTER_DATA_DEAD_LETTER_ROUTING_KEY);
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
