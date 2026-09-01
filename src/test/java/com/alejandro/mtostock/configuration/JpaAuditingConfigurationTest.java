package com.alejandro.mtostock.configuration;

import com.alejandro.mtostock.configuration.security.CurrentUserService;
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

class JpaAuditingConfigurationTest {

    private final JpaAuditingConfiguration configuration = new JpaAuditingConfiguration();

    private final AuditorAware<String> auditorAware = configuration.auditorAware(new CurrentUserService());

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    @Test
    void auditorAwareReturnsTheAuthenticatedUsername() {
        withRequest();
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

        assertEquals("alejandro", auditorAware.getCurrentAuditor().orElseThrow());
    }

    /**
     * Escritura sin usuario que se espera: un proceso de fondo corre fuera del ciclo de una
     * peticion, y esa es la senal que lo separa de un hilo que si atiende a alguien.
     */
    @Test
    void auditorAwareReturnsSystemOutsideOfAnHttpRequest() {
        assertEquals(JpaAuditingConfiguration.SYSTEM_ACTOR, auditorAware.getCurrentAuditor().orElseThrow());
    }

    /**
     * Escritura sin usuario que no se espera. Se registra aparte de {@code system} porque el dato
     * sobrevive a la incidencia: quien audite meses despues necesita separar «lo hizo un proceso»
     * de «no se sabe».
     */
    @Test
    void auditorAwareReturnsUnknownWhenAnHttpRequestReachesTheWriteWithoutAUser() {
        withRequest();

        assertEquals(JpaAuditingConfiguration.UNKNOWN_ACTOR, auditorAware.getCurrentAuditor().orElseThrow());
    }

    private static void withRequest() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }
}
