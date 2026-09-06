package com.alejandro.mtostock.configuration;

import com.alejandro.mtostock.configuration.security.CurrentUserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.Optional;

/**
 * Resuelve quién está detrás de una escritura, para las dos auditorías que conviven en el proyecto.
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
 *
 * <h2>Por qué esto es estático y no un bean</h2>
 *
 * <p>Lo llaman dos sitios: el {@code AuditorAware} de {@link JpaAuditingConfiguration}, que sí es un
 * bean, y {@code AuditRevisionListener}, que no lo es —a un {@code RevisionListener} lo instancia
 * Hibernate mientras construye el {@code SessionFactory}, es decir, en mitad del arranque del
 * contexto—. Resolver el actor sin depender del contenedor hace que las dos rutas den exactamente
 * la misma respuesta sin que el orden de arranque tenga nada que decir. Si fueran dos
 * implementaciones distintas podrían discrepar, y dos «quién» que no coinciden en una auditoría son
 * peor que ninguno.</p>
 *
 * <p>{@link CurrentUserService} no tiene estado —solo lee el {@code SecurityContextHolder}—, así que
 * mantener aquí una instancia propia no crea una segunda lectura del contexto de seguridad: sigue
 * habiendo una sola clase que lo lee.</p>
 */
public final class AuditActorResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuditActorResolver.class);

    /** Escritura sin usuario que se espera: procesos internos de la propia aplicación. */
    public static final String SYSTEM_ACTOR = "system";

    /** Escritura sin usuario que no se espera: la identidad se ha perdido o nunca llegó. */
    public static final String UNKNOWN_ACTOR = "unknown";

    private static final CurrentUserService CURRENT_USER = new CurrentUserService();

    private AuditActorResolver() {
    }

    /**
     * @return el nombre de usuario autenticado, o {@link #SYSTEM_ACTOR} / {@link #UNKNOWN_ACTOR}
     *         según la distinción descrita arriba. Nunca {@code null}.
     */
    public static String currentActor() {
        Optional<String> username = CURRENT_USER.getUsername();
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
     * El {@code sub} del token, que a diferencia del nombre de usuario no cambia si alguien se
     * renombra en Keycloak. Solo lo usa el historial de Envers; las columnas {@code created_by} /
     * {@code updated_by} guardan el nombre porque es lo que se lee.
     */
    public static Optional<String> currentUserId() {
        return CURRENT_USER.getUserId();
    }

    /**
     * Los procesos de fondo corren fuera del ciclo de una petición, así que la ausencia de atributos
     * de petición es lo que los separa de un hilo que sí atiende a alguien.
     */
    private static boolean isWithinRequest() {
        return RequestContextHolder.getRequestAttributes() != null;
    }
}
