package com.alejandro.mtostock.configuration.security;

/**
 * Roles de cliente de Keycloak que la API comprueba, sin el prefijo {@code ROLE_} porque es el
 * formato que esperan {@code hasRole(...)} y {@code hasAnyRole(...)}.
 * <p>
 * El modelo tiene dos niveles: estos son permisos concretos y se declaran como roles de cliente de
 * {@code mto-stock-api}; los perfiles de negocio ({@code mto-stock-viewer},
 * {@code mto-stock-operator}, {@code mto-stock-admin}, {@code mto-stock-ops}) son roles
 * <em>compuestos</em> de realm que los agrupan. Así se cambia lo que puede hacer un perfil desde
 * Keycloak sin desplegar.
 * <p>
 * Los nombres en Keycloak van en minúsculas y con guiones ({@code stock-read}): el
 * {@link KeycloakJwtAuthenticationConverter} los normaliza a mayúsculas con guion bajo, que es la
 * forma que aparece aquí. Evítense los dos puntos como separador, porque sobreviven a la
 * normalización y obligarían a escribir {@code hasRole("STOCK:READ")}.
 */
public final class SecurityRoles {

    private SecurityRoles() {
    }

    /** Consulta del catálogo, del stock derivado, del histórico de movimientos y de las reservas. */
    public static final String STOCK_READ = "STOCK_READ";

    /**
     * Alta y modificación del catálogo y operaciones ordinarias del almacén: entradas, salidas,
     * transferencias y ciclo de vida de las reservas. Todas ellas parten de un documento —un
     * albarán, un consumo de obra— con el que la anotación del stock se puede contrastar después.
     */
    public static final String STOCK_WRITE = "STOCK_WRITE";

    /** Cancelación de reservas. Separado de la escritura a propósito. */
    public static final String STOCK_DELETE = "STOCK_DELETE";

    /**
     * Ajustes de inventario. Se pide <b>además</b> de {@link #STOCK_WRITE} porque un ajuste es la
     * única escritura que reescribe el saldo sin contrapartida documental: es lo que se usa tras un
     * recuento, y también lo que cuadraría un descuadre provocado a mano. Con un solo permiso,
     * cualquiera que pudiera registrar una salida podría además hacerla desaparecer del saldo.
     */
    public static final String STOCK_ADJUST = "STOCK_ADJUST";

    /** Lectura de los endpoints de operación expuestos por Actuator. */
    public static final String OPS_METRICS = "OPS_METRICS";

    /**
     * Operaciones de Actuator que <b>modifican</b> algo. Va aparte de {@link #OPS_METRICS} porque
     * leer métricas es observar y cambiar el estado del sistema no lo es; se concede por clase de
     * operación y no por endpoint concreto, de modo que un endpoint de escritura que se exponga más
     * adelante no herede en silencio el permiso de lectura.
     */
    public static final String OPS_WRITE = "OPS_WRITE";
}
