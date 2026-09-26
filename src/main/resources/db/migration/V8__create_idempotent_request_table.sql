-- Escrituras idempotentes: la cabecera Idempotency-Key de POST /reservations y de
-- POST /movements/outputs.
--
-- Un cliente que manda una reserva o una salida y se queda sin respuesta (un tiempo agotado, un corte
-- de red) no sabe si stock la aplico. Si reintenta, la duplica: dos reservas reteniendo el mismo
-- material, o dos salidas descontandolo dos veces. Con la misma clave, el reintento encuentra aqui la
-- peticion anterior y recibe lo que creo, sin volver a validar existencias ni tocar el saldo.
--
-- La garantia es la restriccion unica, no una comprobacion en codigo. La escritura reclama su clave
-- con un INSERT ... ON CONFLICT DO NOTHING al principio de su propia transaccion, y una segunda
-- peticion simultanea con la misma clave espera en el indice unico hasta que la primera termina: si
-- la primera confirmo, la segunda no inserta nada y lee lo que se creo; si revirtio (stock dijo que
-- no), la reclamacion se fue con ella y la segunda se ejecuta como si fuera la primera.
--
-- No se audita: se escribe solo con SQL nativo, que Envers no ve (ver V7). Las filas no caducan: hay
-- una por escritura con clave, del mismo orden que el libro de movimientos.

CREATE TYPE idempotent_operation AS ENUM (
    'RESERVATION',
    'OUTPUT'
);

CREATE TABLE idempotent_request (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    operation idempotent_operation NOT NULL,
    -- La clave tal como llego en la cabecera: de 1 a 255 caracteres ASCII visibles.
    idempotency_key varchar(255) NOT NULL,
    -- SHA-256 del cuerpo de la peticion. La misma clave con otro cuerpo no es una peticion nueva sino
    -- un error del cliente, y se responde 409 sin escribir nada.
    request_hash varchar(64) NOT NULL,
    -- Lo que creo la peticion: la reserva o el movimiento de salida, segun operation, y por eso sin
    -- clave ajena (ninguna de las dos tablas borra filas). Solo esta vacio dentro de la transaccion
    -- que reclama la clave, entre la reclamacion y la escritura, y esa transaccion no confirma sin
    -- rellenarlo: fuera de ella nadie lo ve vacio.
    resource_id uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    -- Quien mando la peticion: el usuario autenticado, el mismo que las columnas de auditoria del
    -- resto de tablas. Forma parte de la clave para que la de un cliente no choque con la de otro.
    created_by varchar(100) NOT NULL,
    updated_by varchar(100) NOT NULL,
    CONSTRAINT uq_idempotent_request_key UNIQUE (operation, created_by, idempotency_key),
    CONSTRAINT chk_idempotent_request_key_not_blank CHECK (length(idempotency_key) > 0)
);

-- No hace falta otro indice: la busqueda de la peticion anterior va por la clave completa, que
-- uq_idempotent_request_key ya indexa.
