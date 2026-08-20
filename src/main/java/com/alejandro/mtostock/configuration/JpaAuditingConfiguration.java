package com.alejandro.mtostock.configuration;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

/**
 * Configures Spring Data JPA auditing for persistence entities.
 */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
@ConditionalOnProperty(name = "spring.data.jpa.auditing.enabled", havingValue = "true", matchIfMissing = true)
public class JpaAuditingConfiguration {

    private static final String ACTOR_HEADER = "X-Actor";
    private static final String SYSTEM_ACTOR = "system";
    private static final int MAX_ACTOR_LENGTH = 100;

    @Bean
    public AuditorAware<String> auditorAware() {
        return () -> Optional.of(currentActor());
    }

    private static String currentActor() {
        RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
        if (!(requestAttributes instanceof ServletRequestAttributes servletRequestAttributes)) {
            return SYSTEM_ACTOR;
        }

        HttpServletRequest request = servletRequestAttributes.getRequest();
        // Temporary request-header based auditing until Spring Security authentication is introduced.
        return sanitizeActor(request.getHeader(ACTOR_HEADER));
    }

    private static String sanitizeActor(String actor) {
        if (actor == null) {
            return SYSTEM_ACTOR;
        }

        String sanitizedActor = actor.trim();
        if (sanitizedActor.isBlank()) {
            return SYSTEM_ACTOR;
        }
        if (sanitizedActor.length() > MAX_ACTOR_LENGTH) {
            return sanitizedActor.substring(0, MAX_ACTOR_LENGTH);
        }
        return sanitizedActor;
    }

}