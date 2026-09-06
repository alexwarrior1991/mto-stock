package com.alejandro.mtostock.configuration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.util.Optional;

/**
 * Configures Spring Data JPA auditing for persistence entities.
 *
 * <p>Estas columnas ({@code created_by} / {@code updated_by} y sus fechas) guardan el <b>último</b>
 * estado: quién tocó la fila la última vez. El historial de valores anteriores es la otra mitad de
 * la auditoría y lo lleva Hibernate Envers — ver {@code docs/07-auditing.md}.</p>
 *
 * <p>Quién escribe lo decide {@link AuditActorResolver}, compartido con el listener de revisiones de
 * Envers para que las dos auditorías no puedan dar respuestas distintas. El razonamiento sobre
 * {@code system} frente a {@code unknown} está allí.</p>
 */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
@ConditionalOnProperty(name = "spring.data.jpa.auditing.enabled", havingValue = "true", matchIfMissing = true)
public class JpaAuditingConfiguration {

    @Bean
    public AuditorAware<String> auditorAware() {
        return () -> Optional.of(AuditActorResolver.currentActor());
    }
}
