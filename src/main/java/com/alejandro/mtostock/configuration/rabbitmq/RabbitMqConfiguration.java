package com.alejandro.mtostock.configuration.rabbitmq;

import com.alejandro.mtostock.application.service.MasterDataEventHandler;
import com.alejandro.mtostock.infrastructure.messaging.rabbitmq.MasterDataEventConsumer;
import com.alejandro.mtostock.infrastructure.messaging.rabbitmq.MasterDataRabbitMqNames;
import com.alejandro.mtostock.infrastructure.messaging.rabbitmq.RabbitListenerContainerFactoryNames;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.amqp.autoconfigure.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Declares the master data channel and wires its consumer.
 *
 * <p>{@code mto-stock} solo consume: no publica nada, así que aquí no hay {@code RabbitTemplate} ni
 * publisher confirms. Lo que sí declara es su propia cola, su DLX, su DLQ y el binding contra el
 * exchange de {@code mto-configuration}, porque una cola pertenece a quien la consume.</p>
 *
 * <p>El exchange se redeclara con exactamente los mismos atributos que usa el emisor (topic,
 * durable, sin auto-delete). La redeclaración es idempotente solo si coinciden: cualquier
 * diferencia hace que el broker responda {@code PRECONDITION_FAILED} y cierre el canal, con lo que
 * se quedan sin declarar la cola y el binding — y sin binding no llega ni un mensaje, sin ningún
 * error visible después del arranque.</p>
 *
 * <p>Todo el bloque se apaga con {@code app.rabbitmq.enabled=false}: sin estos beans nadie abre una
 * conexión y la aplicación arranca igual sin broker, que es lo que necesitan los tests y un
 * entorno donde el canal todavía no está montado.</p>
 */
@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(MasterDataRabbitProperties.class)
@ConditionalOnProperty(prefix = "app.rabbitmq", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RabbitMqConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(RabbitMqConfiguration.class);

    private final MasterDataRabbitProperties properties;

    /** Exchange del emisor. Se replica tal cual; ver el javadoc de la clase. */
    @Bean
    public TopicExchange masterDataExchange() {
        return new TopicExchange(properties.exchange(), true, false);
    }

    /**
     * Cola de este servicio, con su descarte configurado en la declaración.
     *
     * <p>Los argumentos de una cola ya creada son inmutables, así que añadir aquí un TTL o un
     * límite obliga a borrarla y recrearla en cada entorno; para eso están las policies del
     * broker.</p>
     */
    @Bean
    public Queue masterDataQueue() {
        return QueueBuilder.durable(properties.queue())
                .withArgument(MasterDataRabbitMqNames.ARG_DEAD_LETTER_EXCHANGE, properties.deadLetterExchange())
                .withArgument(MasterDataRabbitMqNames.ARG_DEAD_LETTER_ROUTING_KEY, properties.deadLetterRoutingKey())
                .build();
    }

    @Bean
    public Binding masterDataBinding(Queue masterDataQueue, TopicExchange masterDataExchange) {
        return BindingBuilder.bind(masterDataQueue).to(masterDataExchange).with(properties.routingKey());
    }

    /**
     * El exchange de descarte es {@code direct} y no {@code topic}: aquí no se enruta por patrón,
     * solo se lleva lo rechazado a una única cola.
     */
    @Bean
    public DirectExchange masterDataDeadLetterExchange() {
        return new DirectExchange(properties.deadLetterExchange(), true, false);
    }

    @Bean
    public Queue masterDataDeadLetterQueue() {
        return QueueBuilder.durable(properties.deadLetterQueue()).build();
    }

    @Bean
    public Binding masterDataDeadLetterBinding(
            Queue masterDataDeadLetterQueue,
            DirectExchange masterDataDeadLetterExchange) {
        return BindingBuilder.bind(masterDataDeadLetterQueue)
                .to(masterDataDeadLetterExchange)
                .with(properties.deadLetterRoutingKey());
    }

    /**
     * Convertidor JSON del canal.
     *
     * <p>Parte del {@link JsonMapper} de la aplicación para no mantener dos configuraciones de
     * Jackson, y le desactiva explícitamente el fallo por propiedades desconocidas. Eso no es un
     * atajo: el emisor puede añadir campos al mensaje sin coordinarse con cada consumidor, y con el
     * fallo activo un campo nuevo mandaría a la DLQ mensajes perfectamente válidos.</p>
     *
     * <p>El tipo destino se infiere de la firma del método anotado con {@code @RabbitListener}. Es
     * necesario porque el emisor publica bytes ya serializados, sin cabecera {@code __TypeId__}:
     * no hay tipo en el mensaje que seguir.</p>
     */
    @Bean
    public MessageConverter masterDataMessageConverter(JsonMapper jsonMapper) {
        return new JacksonJsonMessageConverter(jsonMapper.rebuild()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build());
    }

    /**
     * Factory del contenedor de listeners.
     *
     * <p>El {@link SimpleRabbitListenerContainerFactoryConfigurer} no es un adorno: declarar esta
     * factory a mano sustituye a la de Spring Boot, y sin pasar por el configurer se descarta en
     * silencio todo {@code spring.rabbitmq.listener.simple.*} — reintentos, backoff y
     * acknowledge-mode incluidos. Un consumidor que fallara no reintentaría nunca, aunque el YAML
     * dijera que sí.</p>
     *
     * <p>{@code defaultRequeueRejected} sí se fija aquí, y no en el YAML, a propósito. Con el valor
     * por defecto de Spring AMQP ({@code true}) un mensaje que falla vuelve a la cabeza de la cola
     * y se reentrega sin fin: el consumidor gira en vacío, la DLQ nunca recibe nada y el canal se
     * queda atascado en el primer mensaje malo. Es la única garantía de este consumidor que no
     * conviene dejar a merced de una línea de configuración.</p>
     */
    @Bean(RabbitListenerContainerFactoryNames.MASTER_DATA)
    public SimpleRabbitListenerContainerFactory masterDataRabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            MessageConverter masterDataMessageConverter) {

        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setMessageConverter(masterDataMessageConverter);
        factory.setDefaultRequeueRejected(false);

        return factory;
    }

    /**
     * Consumidor del canal.
     *
     * <p>Es un {@code @Bean} y no un {@code @Component} para que herede la condición de esta clase:
     * con {@code app.rabbitmq.enabled=false} no hay factory que lo soporte, y un consumidor
     * escaneado por su cuenta fallaría al arrancar buscándola.</p>
     */
    @Bean
    @ConditionalOnProperty(
            prefix = "app.rabbitmq.master-data", name = "listener-enabled",
            havingValue = "true", matchIfMissing = true)
    public MasterDataEventConsumer masterDataEventConsumer(MasterDataEventHandler masterDataEventHandler) {
        LOGGER.info("Master data consumer enabled: queue={}, boundTo={} with routingKey={}, "
                        + "deadLetterQueue={} via {} with routingKey={}",
                properties.queue(), properties.exchange(), properties.routingKey(),
                properties.deadLetterQueue(), properties.deadLetterExchange(), properties.deadLetterRoutingKey());

        return new MasterDataEventConsumer(masterDataEventHandler);
    }
}
