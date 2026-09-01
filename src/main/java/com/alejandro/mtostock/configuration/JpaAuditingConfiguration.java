package com.alejandro.mtostock.configuration;

import com.alejandro.mtostock.configuration.security.CurrentUserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.Optional;

/**
 * Configures Spring Data JPA auditing for persistence entities.
 *
 * <p>El autor sale del usuario autenticado, no de una cabecera: antes de la seguridad la columna la
 * rellenaba {@code X-Actor}, que cualquier cliente podía poner a lo que quisiera y dejaba una
 * auditoría que no probaba nada.</p>
 *
 * <p>Cuando no hay usuario autenticado hay dos situaciones muy distintas, y registrarlas igual las
 * volvía indistinguibles:</p>
 *
 * <ul>
 *   <li>Un proceso de fondo —un listener de RabbitMQ, una tarea programada— escribe sin que haya
 *       nadie detrás. Es lo normal y se registra como {@code system}.</li>
 *   <li>Una petición HTTP llega hasta la escritura sin autenticación en el contexto. Eso no debería
 *       ocurrir: o la cadena de filtros ha dejado pasar algo, o el contexto se ha perdido por el
 *       camino. Se registra como {@code unknown} y se deja constancia en el log.</li>
 * </ul>
 *
 * <p>Distinguirlas importa porque el dato sobrevive a la incidencia: quien audite meses después
 * quién movió un material necesita poder separar «lo hizo un proceso» de «no se sabe».</p>
 */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
@ConditionalOnProperty(name = "spring.data.jpa.auditing.enabled", havingValue = "true", matchIfMissing = true)
public class JpaAuditingConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(JpaAuditingConfiguration.class);

    /** Escritura sin usuario que se espera: procesos internos de la propia aplicación. */
    public static final String SYSTEM_ACTOR = "system";

    /** Escritura sin usuario que no se espera: la identidad se ha perdido o nunca llegó. */
    public static final String UNKNOWN_ACTOR = "unknown";

    @Bean
    public AuditorAware<String> auditorAware(CurrentUserService currentUserService) {
        return () -> Optional.of(currentActor(currentUserService));
    }

    private static String currentActor(CurrentUserService currentUserService) {
        Optional<String> username = currentUserService.getUsername();
        if (username.isPresent()) {
            return username.get();
        }

        if (!isWithinRequest()) {
            return SYSTEM_ACTOR;
        }

        LOGGER.warn("Write without an authenticated user during an HTTP request: audited as '{}'. "
                + "Either the filter chain let the request through, or the security context was "
                + "lost between threads.", UNKNOWN_ACTOR);

        return UNKNOWN_ACTOR;
    }

    /**
     * Los procesos de fondo corren fuera del ciclo de una petición, así que la ausencia de atributos
     * de petición es lo que los separa de un hilo que sí atiende a alguien.
     */
    private static boolean isWithinRequest() {
        return RequestContextHolder.getRequestAttributes() != null;
    }
}
