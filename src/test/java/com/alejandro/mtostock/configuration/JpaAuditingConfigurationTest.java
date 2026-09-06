package com.alejandro.mtostock.configuration;

import com.alejandro.mtostock.infrastructure.persistence.audit.AuditRevision;
import com.alejandro.mtostock.infrastructure.persistence.audit.AuditRevisionListener;
import com.alejandro.mtostock.infrastructure.persistence.audit.AuditRevisionSource;
import com.alejandro.mtostock.infrastructure.persistence.audit.MessagingAuditContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.AuditorAware;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Las dos auditorías comparten resolutor de actor a propósito, así que se prueban juntas: lo que se
 * está fijando es que las columnas {@code updated_by} y {@code audit_revision.username} no puedan
 * contestar cosas distintas a la misma pregunta.
 *
 * <p>El listener de revisiones no necesita contenedor porque no inyecta nada — todo lo que lee son
 * holders estáticos. Ese es justamente el motivo de diseñarlo así.</p>
 */
class JpaAuditingConfigurationTest {

    private final AuditorAware<String> auditorAware = new JpaAuditingConfiguration().auditorAware();

    private final AuditRevisionListener revisionListener = new AuditRevisionListener();

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
        MessagingAuditContext.clear();
    }

    @Test
    void auditorAwareReturnsTheAuthenticatedUsername() {
        withRequest();
        withAuthenticatedUser();

        assertEquals("alejandro", auditorAware.getCurrentAuditor().orElseThrow());
    }

    /**
     * Escritura sin usuario que se espera: un proceso de fondo corre fuera del ciclo de una
     * peticion, y esa es la senal que lo separa de un hilo que si atiende a alguien.
     */
    @Test
    void auditorAwareReturnsSystemOutsideOfAnHttpRequest() {
        assertEquals(AuditActorResolver.SYSTEM_ACTOR, auditorAware.getCurrentAuditor().orElseThrow());
    }

    /**
     * Escritura sin usuario que no se espera. Se registra aparte de {@code system} porque el dato
     * sobrevive a la incidencia: quien audite meses despues necesita separar «lo hizo un proceso»
     * de «no se sabe».
     */
    @Test
    void auditorAwareReturnsUnknownWhenAnHttpRequestReachesTheWriteWithoutAUser() {
        withRequest();

        assertEquals(AuditActorResolver.UNKNOWN_ACTOR, auditorAware.getCurrentAuditor().orElseThrow());
    }

    @Test
    void revisionRecordsTheAuthenticatedUserAndTheRequestItCameFrom() {
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/v1/materials/42");
        request.addHeader("X-Correlation-Id", "corr-1");
        request.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.1");
        request.addHeader("User-Agent", "curl/8.5.0");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        withAuthenticatedUser();

        AuditRevision revision = new AuditRevision();
        revisionListener.newRevision(revision);

        assertEquals("alejandro", revision.getUsername());
        assertEquals("subject-1", revision.getUserId());
        assertEquals(AuditRevisionSource.HTTP, revision.getSource());
        assertEquals("corr-1", revision.getCorrelationId());
        assertEquals("PUT", revision.getRequestMethod());
        assertEquals("/api/v1/materials/42", revision.getRequestUri());
        assertEquals("curl/8.5.0", revision.getUserAgent());
    }

    /**
     * Detras del gateway, getRemoteAddr() devuelve el gateway. Guardar ese salto y no el del cliente
     * haria que todas las revisiones vinieran de la misma IP.
     */
    @Test
    void revisionRecordsTheFirstForwardedHopAsTheClientAddress() {
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/v1/materials/42");
        request.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        AuditRevision revision = new AuditRevision();
        revisionListener.newRevision(revision);

        assertEquals("203.0.113.7", revision.getIpAddress());
    }

    /**
     * Sin esto, una escritura provocada por un evento de datos maestros quedaria como una escritura
     * de {@code system} sin nada que la ate al mensaje que la causo.
     */
    @Test
    void revisionRecordsTheMessageIdWhenTheWriteComesFromAnEvent() {
        MessagingAuditContext.set(new MessagingAuditContext.Context("op-123", "mto-configuration"));

        AuditRevision revision = new AuditRevision();
        revisionListener.newRevision(revision);

        assertEquals(AuditActorResolver.SYSTEM_ACTOR, revision.getUsername());
        assertEquals(AuditRevisionSource.MESSAGING, revision.getSource());
        assertEquals("op-123", revision.getCorrelationId());
        assertNull(revision.getRequestUri());
    }

    @Test
    void revisionOutsideOfAnyRequestOrMessageIsRecordedAsSystem() {
        AuditRevision revision = new AuditRevision();
        revisionListener.newRevision(revision);

        assertEquals(AuditActorResolver.SYSTEM_ACTOR, revision.getUsername());
        assertEquals(AuditRevisionSource.SYSTEM, revision.getSource());
        assertNull(revision.getCorrelationId());
    }

    /**
     * Una peticion en curso es la evidencia mas fuerte del origen. Si ademas quedara contexto de
     * mensajeria colgado de un hilo reutilizado, no debe ganar.
     */
    @Test
    void anInFlightRequestWinsOverALeftoverMessagingContext() {
        withRequest();
        MessagingAuditContext.set(new MessagingAuditContext.Context("op-123", "mto-configuration"));

        AuditRevision revision = new AuditRevision();
        revisionListener.newRevision(revision);

        assertEquals(AuditRevisionSource.HTTP, revision.getSource());
    }

    private static void withRequest() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    private static void withAuthenticatedUser() {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                Jwt.withTokenValue("token")
                        .header("alg", "RS256")
                        .subject("subject-1")
                        .claim("preferred_username", "alejandro")
                        .issuedAt(Instant.EPOCH)
                        .expiresAt(Instant.EPOCH.plusSeconds(300))
                        .build(),
                AuthorityUtils.createAuthorityList("ROLE_STOCK_WRITE"),
                "alejandro"
        ));
    }
}
