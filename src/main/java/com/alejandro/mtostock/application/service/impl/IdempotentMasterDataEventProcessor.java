package com.alejandro.mtostock.application.service.impl;

import com.alejandro.mtostock.application.dto.messaging.InboxMessageCommand;
import com.alejandro.mtostock.application.dto.messaging.InboxProcessingResult;
import com.alejandro.mtostock.application.dto.messaging.MasterDataChangedMessage;
import com.alejandro.mtostock.application.dto.messaging.MasterDataEventContext;
import com.alejandro.mtostock.application.service.InboxMessageService;
import com.alejandro.mtostock.application.service.MasterDataEventHandler;
import com.alejandro.mtostock.application.service.MasterDataEventProcessor;
import com.alejandro.mtostock.infrastructure.persistence.repository.InboxMessageRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

/**
 * Joins the inbox and the master data handler: the handler runs once per message, ever.
 *
 * <p>Esta clase no es transaccional, y eso es deliberado. La transacción del intento la abre
 * {@link InboxMessageService#process}, de modo que cuando el {@code catch} de aquí se ejecuta esa
 * transacción ya ha revertido y ha soltado sus bloqueos: solo entonces se puede escribir el estado
 * fallido sin que las dos transacciones se esperen mutuamente.</p>
 *
 * <p>La excepción se relanza siempre. El contenedor de listeners es quien decide reintentar y
 * acabar mandando el mensaje a la DLQ; tragársela aquí confirmaría al broker un mensaje que no se
 * ha aplicado.</p>
 */
@Service
@ConditionalOnBean(InboxMessageRepository.class)
@RequiredArgsConstructor
class IdempotentMasterDataEventProcessor implements MasterDataEventProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(IdempotentMasterDataEventProcessor.class);

    private final InboxMessageService inboxMessageService;
    private final MasterDataEventHandler masterDataEventHandler;

    @Override
    public InboxProcessingResult process(InboxMessageCommand command, MasterDataChangedMessage message) {
        try {
            MasterDataEventContext context = new MasterDataEventContext(command.sequenceNumber());

            return inboxMessageService.process(command, () -> masterDataEventHandler.handle(message, context));
        } catch (RuntimeException failure) {
            LOGGER.error("Master data message failed and was recorded as failed in the inbox: "
                            + "messageId={}, sourceService={}, eventType={}",
                    command.messageId(), command.sourceService(), command.eventType(), failure);

            inboxMessageService.recordFailure(command, failure);

            throw failure;
        }
    }
}
