package com.alejandro.mtostock.infrastructure.messaging.outbox;

import com.alejandro.mtostock.application.dto.messaging.AsynchronousMessage;
import com.alejandro.mtostock.application.dto.messaging.DomainEvent;
import com.alejandro.mtostock.application.service.DomainEventPublisher;
import com.alejandro.mtostock.configuration.rabbitmq.StockEventsProperties;
import com.alejandro.mtostock.infrastructure.messaging.rabbitmq.StockRabbitMqNames;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

/**
 * Escribe cada evento propio en el outbox, envuelto en el sobre comun, para que el relay lo publique
 * en {@code mto.stock.exchange} con la clave {@code mto.stock.<entidad>.<evento>}.
 *
 * <p>El agregado del outbox es la entidad del evento ({@code reservation}-{@code <id>},
 * {@code material}-{@code <id>}), de modo que el orden estricto por agregado del relay conserva el
 * orden en que se generaron los eventos de una misma entidad aunque el primero falle y se
 * reintente.</p>
 */
@Slf4j
@RequiredArgsConstructor
public class OutboxDomainEventPublisher implements DomainEventPublisher {

    private final AsynchronousMessageFactory messageFactory;
    private final OutboxService outboxService;
    private final StockEventsProperties properties;

    @Override
    public void publish(DomainEvent event) {
        publish(UUID.randomUUID(), event);
    }

    @Override
    public void publish(UUID operationId, DomainEvent event) {
        String eventType = StockRabbitMqNames.eventType(event.entityName(), event.eventName());

        AsynchronousMessage<DomainEvent> message = messageFactory.create(
                operationId,
                event.entityName() + "-" + event.entityId(),
                eventType,
                event
        );

        outboxService.save(
                event.entityName(),
                event.entityId(),
                eventType,
                properties.exchange(),
                StockRabbitMqNames.routingKey(event.entityName(), event.eventName()),
                message
        );

        log.debug("Domain event in the outbox: {} {} {} (operationId={}, actor={})",
                event.entityName(), event.entityId(), event.eventName(), operationId, message.actor());
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
