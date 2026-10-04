-- La salida de las reservas consumidas antes de que consumir escribiera el libro.
--
-- Hasta este cambio, POST /reservations/{id}/consume bajaba el fisico y lo reservado de
-- inventory_balance sin escribir ninguna salida en stock_movement, asi que el libro dejaba de contar
-- lo consumido y ya no cuadraba con el saldo. Desde ahora consumir escribe su salida (OUTPUT, con la
-- reserva). Esta migracion escribe la que falta de cada reserva consumida antes: con el material, el
-- almacen, el proyecto y la cantidad de la reserva, cuando se consumio (released_at, que el CHECK de
-- la tabla exige en toda reserva que no esta activa) y a nombre de quien la consumio (updated_by,
-- porque consumir es el ultimo cambio de una reserva).
--
-- Una reserva consumida con una salida de movimientos (POST /movements/outputs con reservationId) ya
-- tiene su OUTPUT, y NOT EXISTS la deja como esta; por lo mismo, escribirla dos veces no duplica nada.
-- No toca inventory_balance, que ya desconto lo consumido. stock_movement no se audita, asi que no
-- hay gemela _aud.
INSERT INTO stock_movement (material_id, warehouse_id, type, quantity, occurred_at, project_id, reservation_id,
                            notes, created_by, updated_by)
SELECT r.material_id,
       r.warehouse_id,
       'OUTPUT'::stock_movement_type,
       r.quantity,
       r.released_at,
       r.project_id,
       r.id,
       'Backfilled by V11: output of a reservation consumed before consume wrote the ledger',
       r.updated_by,
       r.updated_by
FROM reservation r
WHERE r.status = 'CONSUMED'
  AND NOT EXISTS (SELECT 1
                  FROM stock_movement m
                  WHERE m.reservation_id = r.id
                    AND m.type = 'OUTPUT');
