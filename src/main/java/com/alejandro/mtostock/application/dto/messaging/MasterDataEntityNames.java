package com.alejandro.mtostock.application.dto.messaging;

import java.util.Set;

/**
 * Logical entity names that {@code mto-configuration} publishes today.
 *
 * <p>Son los valores que llegan en {@code MasterDataChangedEvent.entityName()}, y no se deducen de
 * ninguna regla: cada entidad del emisor los fija a mano en su {@code @PublishMasterDataEvent}. Por
 * eso están aquí como constantes y no como una convención — escribir el nombre a mano en cada
 * manejador es la forma habitual de que una errata deje un evento sin atender sin que falle
 * nada.</p>
 *
 * <p>La lista es lo que el emisor publica <b>hoy</b>, no un contrato cerrado: {@code mto-configuration}
 * puede añadir entidades sin avisar, y por eso el despachador ignora lo que no reconoce en vez de
 * fallar. Todas describen infraestructura ferroviaria —el diseño de la catenaria que se va a
 * construir—, no materiales ni almacenes.</p>
 */
public final class MasterDataEntityNames {

    /** Paquete de ejecución: la unidad de obra, con sus fechas, longitud y empresa. */
    public static final String EXECUTION_PACKAGE = "execution-package";

    /** Estación. */
    public static final String STATION = "station";

    /** Vía. */
    public static final String TRACK = "track";

    /** Perfil: el punto kilométrico donde se apoya la catenaria, con su poste y cimentación. */
    public static final String PROFILE = "profile";

    /** Ménsula. */
    public static final String CANTILEVER = "cantilever";

    /** Brazo de atirantado. */
    public static final String STEADY_ARM = "steady-arm";

    /** Seccionador. */
    public static final String DISCONNECTOR = "disconnector";

    /** Aislador de sección. */
    public static final String SECTION_INSULATOR = "section-insulator";

    /** Solo para documentación y tests; el despachador no la usa para filtrar. */
    public static final Set<String> PUBLISHED_TODAY = Set.of(
            EXECUTION_PACKAGE, STATION, TRACK, PROFILE,
            CANTILEVER, STEADY_ARM, DISCONNECTOR, SECTION_INSULATOR);

    private MasterDataEntityNames() {
    }
}
