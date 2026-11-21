-- Los indices de las dos listas que crecen sin limite, medidos con EXPLAIN (ANALYZE) sobre 2,1 M de
-- apuntes y 500 000 reservas (docs/03-database.md, Indexes).
--
-- No llevan CONCURRENTLY: Flyway corre cada migracion en una transaccion y hoy las tablas son
-- pequenas. Sobre una tabla ya grande, CREATE INDEX bloquea sus escrituras mientras dura: crea antes
-- cada indice a mano con CREATE INDEX CONCURRENTLY y el mismo nombre, y esta migracion (IF NOT EXISTS)
-- ya no hara nada.

-- El libro entero (GET /movements sin material, almacen ni tipo, o solo con fechas) en su orden,
-- occurredAt desc, id. Sin el, cada pagina leia y ordenaba la tabla entera: 283 ms -> 0,4 ms.
CREATE INDEX IF NOT EXISTS idx_stock_movement_occurred_at
    ON stock_movement (occurred_at DESC, id);

-- El libro de un material sin almacen. idx_stock_movement_material_warehouse_occurred_at lleva el
-- almacen en medio y no da ese orden (81 ms con 200 000 apuntes de un material), y con el indice de
-- arriba el planificador recorre el libro entero buscando al material: 541 ms para uno cuyos apuntes
-- son todos viejos. Con este, 0,2 ms. El de material y almacen sigue: es el del libro de un material
-- en un almacen y el de «materiales que han pasado por un almacen».
CREATE INDEX IF NOT EXISTS idx_stock_movement_material_occurred_at
    ON stock_movement (material_id, occurred_at DESC, id);

-- La lista de reservas en su orden, reservedAt desc, id: 106 ms -> 0,4 ms.
CREATE INDEX IF NOT EXISTS idx_reservation_reserved_at
    ON reservation (reserved_at DESC, id);

-- Las reservas de un material en cualquier estado: idx_reservation_active_material_warehouse es
-- parcial (solo las ACTIVE) y no servia a la lista ni a su recuento: 49 ms -> 0,2 ms.
CREATE INDEX IF NOT EXISTS idx_reservation_material_id
    ON reservation (material_id);

-- inventory_balance tenia dos indices que repetian su clave unica (material_id, warehouse_id): el
-- mismo par de columnas y su prefijo. Todo lo que los usaba (la suma por material, la fila de un
-- material en un almacen, los UPDATE del saldo, la lista bajo minimo y la comprobacion de la clave
-- ajena del material) sale igual por uq_inventory_balance_material_warehouse. El de warehouse_id se
-- queda: es el de su clave ajena.
DROP INDEX IF EXISTS idx_inventory_balance_material_id;
DROP INDEX IF EXISTS idx_inventory_balance_material_warehouse;
