package com.alejandro.mtostock.configuration.messaging;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the message signature check and the signer of what this service publishes.
 *
 * <p>No está bajo la condición de {@code app.rabbitmq.enabled}: la firma es del mensaje, no del
 * transporte, y con el consumidor y el outbox apagados los beans simplemente no los usa nadie. Los
 * dos leen el mismo secreto: lo que se firma aquí se comprueba en {@code mto-notification} con el
 * mismo valor, como lo que firma {@code mto-configuration} se comprueba aquí.</p>
 */
@Configuration
@EnableConfigurationProperties(MessageSignatureProperties.class)
public class MessagingConfiguration {

    @Bean
    public MessagePayloadSignatureVerifier messagePayloadSignatureVerifier(MessageSignatureProperties properties) {
        return new MessagePayloadSignatureVerifier(properties);
    }

    @Bean
    public MessagePayloadSignature messagePayloadSignature(MessageSignatureProperties properties) {
        return new MessagePayloadSignature(properties);
    }
}
