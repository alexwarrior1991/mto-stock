package com.alejandro.mtostock.infrastructure.messaging.outbox;

import com.alejandro.mtostock.application.dto.messaging.MessageActor;
import com.alejandro.mtostock.configuration.security.CurrentUserService;
import com.alejandro.mtostock.infrastructure.persistence.audit.MessagingAuditContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Lo que un mensaje sabe de la operacion que lo genero: quien la pidio y con que identificador.
 *
 * <p>Se lee en el hilo que escribe el outbox, que es el que todavia tiene el contexto: el
 * {@code SecurityContext} de la peticion y, como {@code AuditRevisionListener}, la cabecera
 * {@code X-Correlation-Id} de la peticion en curso o, si la escritura viene de un mensaje de datos
 * maestros, el identificador de ese mensaje ({@link MessagingAuditContext}). Asi lo que
 * {@code mto-notification} registra se cruza con la revision de Envers y con la fila del inbox por
 * el mismo valor.</p>
 */
@Slf4j
@RequiredArgsConstructor
public class MessageContextResolver {

    static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    /**
     * La cabecera viene de fuera y no la valida nadie mas: se acepta un identificador imprimible y
     * acotado (lo que cabe en {@code audit_revision.correlation_id}), y cualquier otra cosa cuenta
     * como ausente en vez de viajar tal cual a los consumidores.
     */
    static final Pattern VALID_CORRELATION_ID = Pattern.compile("[\\x21-\\x7E]{1,200}");

    private final CurrentUserService currentUserService;

    /**
     * Nunca nulo: lo que no tiene usuario se dice como {@code SYSTEM} en vez de callarse, para que
     * el consumidor distinga «el emisor no lo dice» de «el emisor dice que fue un proceso».
     */
    public MessageActor currentActor() {
        Optional<Authentication> authentication = currentUserService.getAuthentication();

        if (authentication.isEmpty()) {
            return MessageActor.system();
        }

        String username = currentUserService.getUsername().orElseGet(() -> authentication.get().getName());
        String id = currentUserService.getUserId().orElse(null);

        return MessageActor.of(id, username);
    }

    /**
     * El identificador de la peticion en curso o del mensaje que se esta procesando; nulo fuera de
     * ambos. Primero HTTP: una peticion en curso es la evidencia mas fuerte de donde viene la
     * escritura, aunque un hilo reutilizado arrastrara un contexto de mensajeria.
     */
    public String currentCorrelationId() {
        String header = requestHeader();
        if (header != null) {
            return header;
        }

        MessagingAuditContext.Context message = MessagingAuditContext.current();
        return message == null ? null : message.messageId();
    }

    private static String requestHeader() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return null;
        }

        String value = attributes.getRequest().getHeader(CORRELATION_ID_HEADER);
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();
        if (!VALID_CORRELATION_ID.matcher(trimmed).matches()) {
            log.debug("Ignoring an {} header that is blank, too long or not printable ASCII", CORRELATION_ID_HEADER);
            return null;
        }

        return trimmed;
    }
}
