-- Las claves de idempotencia caducan (V8 decia que no).
--
-- Una clave solo sirve mientras el cliente puede reintentar la peticion que la uso. Pasado ese plazo
-- la fila ya no protege de nada, y sin purga la tabla crecia con cada reserva y cada salida con clave.
-- Una tarea programada borra por lotes las que se usaron por primera vez hace mas de
-- app.idempotency.retention (30 dias por defecto), las mas viejas primero; un reintento con una clave
-- olvidada es una peticion nueva. Lo que creo cada clave (la reserva o el movimiento) no se toca.
--
-- El plazo es el contrato con los clientes: mto-maintenance reintenta solo sus peticiones sin
-- respuesta cada pocos minutos mientras stock le responde, asi que no deja ninguna tanto tiempo.
--
-- Este indice es el de esa purga, que filtra y ordena por created_at. La busqueda de la peticion
-- anterior sigue yendo por la clave completa, que ya indexa uq_idempotent_request_key.

CREATE INDEX idx_idempotent_request_created_at ON idempotent_request (created_at);
