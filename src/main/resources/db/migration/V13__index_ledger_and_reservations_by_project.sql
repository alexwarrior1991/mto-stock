-- El libro y las reservas de un proyecto, en su orden. Medido con EXPLAIN (ANALYZE) sobre 3,08 M de
-- apuntes y 600 000 reservas, con cada proyecto en su epoca (una obra dura unos meses), con cada
-- filtro de las dos listas (docs/03-database.md, Indexes).
--
-- Sin CONCURRENTLY: Flyway corre la migracion en una transaccion y hoy las tablas son pequenas. Sobre
-- una ya grande, crea antes cada indice a mano con CREATE INDEX CONCURRENTLY y el mismo nombre, y esta
-- migracion (IF NOT EXISTS) ya no hara nada.

-- El libro de un proyecto. Con solo idx_stock_movement_project_id (el proyecto, sin fecha), el
-- planificador recorria el libro entero por fecha buscando al proyecto: 34 ms si la obra es la de
-- ahora, 3,5 s si es de hace un anio y 6,8 s si es de las primeras. Con este, 0,3 ms en los tres, y
-- su recuento sale del indice (10 ms -> 0,8 ms).
CREATE INDEX IF NOT EXISTS idx_stock_movement_project_occurred_at
    ON stock_movement (project_id, occurred_at DESC, id)
    WHERE project_id IS NOT NULL;

-- El de antes queda cubierto por el nuevo, que empieza por la misma columna con la misma condicion:
-- las paginas profundas, el recuento con otro filtro y la comprobacion de la clave ajena del proyecto
-- salen por el igual o mejor.
DROP INDEX IF EXISTS idx_stock_movement_project_id;

-- Las reservas de un proyecto: 341 ms para una obra vieja (recorria todas las reservas por fecha),
-- 0,3 ms con este. idx_reservation_project_status se queda: es el de las reservas de un proyecto en
-- un estado.
CREATE INDEX IF NOT EXISTS idx_reservation_project_reserved_at
    ON reservation (project_id, reserved_at DESC, id);
