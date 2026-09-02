-- Patron Inbox del lado consumidor: la contraparte del Outbox que ya implementa mto-configuration.
--
-- El outbox garantiza que un evento se publica al menos una vez; el inbox garantiza que se APLICA
-- exactamente una vez. La proteccion contra duplicados vive en la restriccion unica de esta tabla y
-- no en el codigo: dos entregas del mismo mensaje pueden llegar a dos instancias a la vez, y una
-- comprobacion en memoria -o un "existe? entonces inserta"- las deja pasar a las dos.

CREATE TYPE inbox_message_status AS ENUM (
    'RECEIVED',
    'PROCESSING',
    'PROCESSED',
    'FAILED'
);

CREATE TABLE inbox_message (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Clave de idempotencia. Es el 'operationId' del sobre publicado por mto-configuration, o el
    -- message_id de AMQP cuando aquel falta. No se trunca nunca: dos identificadores distintos que
    -- se recortasen al mismo valor harian que un evento legitimo se descartase como duplicado.
    message_id varchar(200) NOT NULL,
    -- Servicio emisor ('origin' del sobre). Forma parte de la clave para que dos emisores no puedan
    -- colisionar en un mismo identificador.
    source_service varchar(100) NOT NULL,
    event_type varchar(150),
    aggregate_type varchar(150),
    aggregate_id varchar(100),
    exchange_name varchar(255),
    routing_key varchar(255),
    queue_name varchar(255),
    -- Dato auxiliar para correlacionar y detectar reenvios con contenido distinto. NO es la clave de
    -- idempotencia: el emisor ya emite identificadores estables y un hash del payload convertiria
    -- dos eventos legitimamente identicos en un duplicado.
    payload_hash varchar(64),
    -- El JSON tal y como llego, caracter a caracter.
    --
    -- 'json' y no 'jsonb' a proposito. jsonb normaliza al guardar: reordena las claves y colapsa los
    -- espacios, de modo que lo que se lee ya no es lo que se recibio y su hash SHA-256 deja de
    -- coincidir con payload_hash. Esta tabla existe para poder responder "que llego exactamente",
    -- y ahi la fidelidad vale mas que los operadores de jsonb, que hoy no usa nadie: no se consulta
    -- dentro del payload. Si algun dia hiciera falta, se anade un indice de expresion sobre
    -- payload::jsonb o una columna generada, sin tocar lo que se almacena.
    payload json NOT NULL,
    status inbox_message_status NOT NULL DEFAULT 'RECEIVED',
    received_at timestamptz NOT NULL DEFAULT now(),
    processed_at timestamptz,
    failed_at timestamptz,
    failure_reason text,
    processing_attempts integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_by varchar(100) NOT NULL DEFAULT 'system',
    updated_by varchar(100) NOT NULL DEFAULT 'system',
    CONSTRAINT uq_inbox_message_message_id_source UNIQUE (message_id, source_service),
    CONSTRAINT chk_inbox_message_processing_attempts_non_negative CHECK (processing_attempts >= 0),
    -- Implicaciones en un solo sentido, no equivalencias: un mensaje que fallo y despues se
    -- reproceso con exito queda PROCESSED conservando su failed_at como historia.
    CONSTRAINT chk_inbox_message_processed_at_present_when_processed CHECK (
        status <> 'PROCESSED' OR processed_at IS NOT NULL
    ),
    CONSTRAINT chk_inbox_message_failed_at_present_when_failed CHECK (
        status <> 'FAILED' OR failed_at IS NOT NULL
    )
);

-- No se crea un indice suelto por message_id: uq_inbox_message_message_id_source ya lo indexa como
-- columna principal, de modo que las busquedas por message_id -con o sin source_service- lo usan.
-- Un indice de mas solo encarece cada insercion, que aqui ocurre una vez por mensaje entrante.
CREATE INDEX idx_inbox_message_status_received_at ON inbox_message (status, received_at);
CREATE INDEX idx_inbox_message_received_at ON inbox_message (received_at);
