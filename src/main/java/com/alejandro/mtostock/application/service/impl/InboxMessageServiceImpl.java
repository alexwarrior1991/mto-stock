package com.alejandro.mtostock.application.service.impl;

import com.alejandro.mtostock.application.dto.messaging.InboxMessageCommand;
import com.alejandro.mtostock.application.dto.messaging.InboxProcessingResult;
import com.alejandro.mtostock.application.exception.ValidationException;
import com.alejandro.mtostock.application.service.InboxMessageService;
import com.alejandro.mtostock.infrastructure.persistence.repository.InboxMessageRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inbox implementation: records the message, claims it and runs the work at most once.
 *
 * <h2>Por qué el estado de fallo va en otra transacción</h2>
 *
 * <p>{@link #process} tiene que revertir cuando el trabajo falla: si no lo hiciera, quedaría un
 * mensaje marcado como aplicado cuyo efecto se deshizo. Pero esa misma reversión se lleva también
 * la fila del inbox, así que marcar el fallo dentro de {@code process} no dejaría rastro. De ahí
 * {@link #recordFailure}, con transacción propia y llamada <b>desde fuera</b>, cuando la del
 * intento ya ha terminado.</p>
 *
 * <p>Llamarla desde dentro sería peor que inútil: la transacción del intento mantiene bloqueada la
 * fila hasta confirmar o revertir, la nueva se quedaría esperando ese bloqueo, y la primera estaría
 * esperando a que la segunda devuelva. Nadie avanza y ningún detector de interbloqueos lo ve,
 * porque una de las dos no espera un bloqueo sino una llamada.</p>
 */
@Service
@RequiredArgsConstructor
class InboxMessageServiceImpl implements InboxMessageService {

    private static final Logger LOGGER = LoggerFactory.getLogger(InboxMessageServiceImpl.class);

    /**
     * El motivo se guarda en una columna {@code text} sin límite, pero se recorta igualmente: el
     * mensaje de una excepción puede arrastrar un payload entero o una consulta completa, y una
     * tabla de auditoría no es el sitio donde almacenarlo sin querer.
     */
    private static final int MAX_FAILURE_REASON_LENGTH = 2000;

    private final InboxMessageRepository inboxMessageRepository;

    @Override
    @Transactional
    public InboxProcessingResult process(InboxMessageCommand command, Runnable processing) {
        validate(command);

        inboxMessageRepository.insertIfMissing(
                command.messageId(),
                command.sourceService(),
                command.eventType(),
                command.aggregateType(),
                command.aggregateId(),
                command.exchangeName(),
                command.routingKey(),
                command.queueName(),
                command.payloadHash(),
                command.payload());

        int claimed = inboxMessageRepository.claimForProcessing(command.messageId(), command.sourceService());

        if (claimed == 0) {
            LOGGER.info("Duplicate message already applied, handler skipped: messageId={}, sourceService={}, "
                            + "eventType={}",
                    command.messageId(), command.sourceService(), command.eventType());
            return InboxProcessingResult.DUPLICATE_SKIPPED;
        }

        processing.run();

        int processed = inboxMessageRepository.markProcessed(command.messageId(), command.sourceService());

        if (processed == 0) {
            // La fila se reclamó hace tres líneas dentro de esta misma transacción, así que no
            // marcarla significa que alguien la ha tocado por debajo. Revertir es lo único honesto:
            // confirmar dejaría el trabajo hecho y el inbox diciendo que no.
            throw new IllegalStateException(
                    "Inbox message could not be marked as processed: messageId=" + command.messageId());
        }

        LOGGER.debug("Message applied and recorded in the inbox: messageId={}, sourceService={}",
                command.messageId(), command.sourceService());

        return InboxProcessingResult.PROCESSED;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(InboxMessageCommand command, Throwable failure) {
        validate(command);

        inboxMessageRepository.recordFailure(
                command.messageId(),
                command.sourceService(),
                command.eventType(),
                command.aggregateType(),
                command.aggregateId(),
                command.exchangeName(),
                command.routingKey(),
                command.queueName(),
                command.payloadHash(),
                command.payload(),
                failureReason(failure));
    }

    /**
     * Sin identificador estable no hay idempotencia posible, así que un comando sin él es un error
     * de programación de quien lo construyó, no un mensaje que haya que reintentar.
     */
    private static void validate(InboxMessageCommand command) {
        if (command == null || command.messageId() == null || command.messageId().isBlank()) {
            throw new ValidationException("Inbox message requires a non-blank messageId");
        }
        if (command.sourceService() == null || command.sourceService().isBlank()) {
            throw new ValidationException("Inbox message requires a non-blank sourceService");
        }
        if (command.payload() == null || command.payload().isBlank()) {
            throw new ValidationException("Inbox message requires a payload");
        }
    }

    private static String failureReason(Throwable failure) {
        if (failure == null) {
            return "unknown failure";
        }

        String reason = failure.getClass().getName()
                + ": "
                + (failure.getMessage() == null ? "no message" : failure.getMessage());

        return reason.length() <= MAX_FAILURE_REASON_LENGTH
                ? reason
                : reason.substring(0, MAX_FAILURE_REASON_LENGTH);
    }
}
