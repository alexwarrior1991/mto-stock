package com.alejandro.mtostock.configuration.cache;

import java.util.List;

/**
 * Names of the master data caches.
 *
 * <p>Están aquí y no sueltos en cada anotación porque el nombre de una caché se escribe dos veces:
 * en el {@code @Cacheable} que la llena y en la configuración que le pone tipo y TTL. Si las dos
 * copias dejan de coincidir no hay ningún error —Redis acepta cualquier nombre—, simplemente la
 * caché pasa a usar la configuración por defecto y nadie se entera.</p>
 *
 * <p>Solo hay cachés de datos maestros. El stock no se cachea: se lee de {@code inventory_balance},
 * que ya es la proyección que evita recalcularlo, y cambia con cada movimiento y cada reserva.</p>
 */
public final class CacheNames {

    /** {@code MaterialResponse} por id. */
    public static final String MATERIALS = "materials";

    /** {@code WarehouseResponse} por id. */
    public static final String WAREHOUSES = "warehouses";

    /** {@code SupplierResponse} por id. */
    public static final String SUPPLIERS = "suppliers";

    /** {@code AssemblyResponse} por id, con su lista de componentes (la BOM viaja dentro). */
    public static final String ASSEMBLIES = "assemblies";

    /** {@code ProjectResponse} por id. */
    public static final String PROJECTS = "projects";

    public static final List<String> ALL = List.of(MATERIALS, WAREHOUSES, SUPPLIERS, ASSEMBLIES, PROJECTS);

    private CacheNames() {
    }
}
