package com.alejandro.mtostock.infrastructure.persistence.audit;

import com.alejandro.mtostock.configuration.AuditActorResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.hibernate.envers.RevisionListener;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Rellena cada {@link AuditRevision} con quién hizo el cambio y desde dónde.
 *
 * <h2>Por qué no inyecta nada</h2>
 *
 * <p>A un {@code RevisionListener} lo instancia Hibernate mientras construye el
 * {@code SessionFactory}, es decir, en mitad del refresco del contexto de Spring. En este stack
 * Hibernate lo resuelve a través del {@code ManagedBeanRegistry}, y Boot registra un
 * {@code SpringBeanContainer}, así que la inyección por constructor <i>probablemente</i>
 * funcionaría; pero cualquier dependencia que necesitara a su vez el {@code EntityManagerFactory}
 * cerraría el ciclo y el contexto no arrancaría. No merece la pena arriesgar el arranque por una
 * inyección: todo lo que lee esta clase —el contexto de seguridad, los atributos de la petición y su
 * propio {@code ThreadLocal}— es estático, así que da igual quién la instancie.</p>
 *
 * <h2>Orden de resolución del origen</h2>
 *
 * <p>Primero HTTP: una petición en curso es la evidencia más fuerte de dónde viene la escritura, y
 * si además hubiera contexto de mensajería colgado de un hilo reutilizado, la petición manda. Solo
 * si no hay petición se mira el mensaje. Si no hay ninguna de las dos, es un proceso interno.</p>
 */
public class AuditRevisionListener implements RevisionListener {

    private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    private static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";
    private static final String USER_AGENT_HEADER = "User-Agent";

    private static final int MAX_CORRELATION_ID_LENGTH = 200;
    private static final int MAX_ADDRESS_LENGTH = 100;
    private static final int MAX_USER_AGENT_LENGTH = 500;
    private static final int MAX_URI_LENGTH = 500;

    @Override
    public void newRevision(Object revisionEntity) {
        AuditRevision revision = (AuditRevision) revisionEntity;

        revision.setUsername(AuditActorResolver.currentActor());
        revision.setUserId(AuditActorResolver.currentUserId().orElse(null));

        HttpServletRequest request = currentRequest();
        if (request != null) {
            fillFromRequest(revision, request);
            return;
        }

        MessagingAuditContext.Context message = MessagingAuditContext.current();
        if (message != null) {
            revision.setSource(AuditRevisionSource.MESSAGING);
            revision.setCorrelationId(truncate(message.messageId(), MAX_CORRELATION_ID_LENGTH));
            return;
        }

        revision.setSource(AuditRevisionSource.SYSTEM);
    }

    private static void fillFromRequest(AuditRevision revision, HttpServletRequest request) {
        revision.setSource(AuditRevisionSource.HTTP);
        revision.setCorrelationId(truncate(request.getHeader(CORRELATION_ID_HEADER), MAX_CORRELATION_ID_LENGTH));
        revision.setIpAddress(truncate(clientAddress(request), MAX_ADDRESS_LENGTH));
        revision.setUserAgent(truncate(request.getHeader(USER_AGENT_HEADER), MAX_USER_AGENT_LENGTH));
        revision.setRequestMethod(request.getMethod());
        revision.setRequestUri(truncate(request.getRequestURI(), MAX_URI_LENGTH));
    }

    /**
     * Detrás del gateway, {@code getRemoteAddr()} devuelve el gateway. El primer salto de
     * {@code X-Forwarded-For} es el cliente original; los siguientes son los proxies por los que
     * pasó.
     */
    private static String clientAddress(HttpServletRequest request) {
        String forwardedFor = request.getHeader(FORWARDED_FOR_HEADER);
        if (forwardedFor == null || forwardedFor.isBlank()) {
            return request.getRemoteAddr();
        }
        return forwardedFor.split(",")[0].trim();
    }

    private static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest()
                : null;
    }

    /**
     * Estos valores vienen de fuera y no los valida nadie: una cabecera larga no puede tumbar la
     * escritura que se está auditando. Se recorta en lugar de fallar porque perder parte de un
     * User-Agent es mejor que perder la revisión entera.
     */
    private static String truncate(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
