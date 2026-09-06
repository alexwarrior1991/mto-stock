-- Historial de cambios (Hibernate Envers).
--
-- Envers escribe una fila en audit_revision por TRANSACCION que toca una entidad auditada, y una
-- fila en <tabla>_aud por entidad cambiada dentro de ella. El "quien y cuando" esta en
-- audit_revision; las gemelas _aud guardan solo el estado de negocio en esa revision, y por eso no
-- repiten created_by / updated_by / created_at / updated_at.
--
-- Se auditan siete tablas: material, supplier, warehouse, project, assembly, assembly_component y
-- reservation. NO se auditan:
--   * stock_movement, que ya es un libro mayor inmutable: auditarlo duplicaria la tabla mas grande
--     del sistema sin anadir informacion.
--   * inventory_balance e inbox_message, que se escriben con SQL nativo. Envers se engancha a los
--     eventos del contexto de persistencia, asi que ahi no registraria nada y una gemela vacia se
--     leeria como "no ha cambiado nunca", que es peor que no tenerla.
--
-- REGLA DE MANTENIMIENTO: toda migracion futura que anada, quite, renombre o cambie de tipo una
-- columna de una de las siete tablas base tiene que aplicar el mismo cambio a su gemela _aud en esa
-- misma migracion, con dos diferencias: en la gemela la columna es siempre nullable y no lleva
-- CHECK, UNIQUE ni clave ajena. Hibernate construye el mapeo de Envers desde la entidad, asi que una
-- columna anadida sin su gemela hace fallar a ddl-auto: validate y la aplicacion no arranca.

-- START WITH 2 deja el 1 para la revision de partida del final de este fichero.
-- INCREMENT BY 1 tiene que coincidir con allocationSize = 1 en AuditRevision.
CREATE SEQUENCE audit_revision_seq START WITH 2 INCREMENT BY 1;

CREATE TABLE audit_revision (
    id integer PRIMARY KEY,
    -- Milisegundos desde epoch, no timestamptz: es el tipo que @RevisionTimestamp admite en
    -- cualquier version de Envers. AuditRevision.getRevisionInstant() lo devuelve como Instant.
    timestamp bigint NOT NULL,
    username varchar(100),
    user_id varchar(100),
    -- HTTP | MESSAGING | SYSTEM | BASELINE. Dice de que canal vino la escritura y, por tanto, a que
    -- espacio de identificadores pertenece correlation_id: la cabecera X-Correlation-Id de una
    -- peticion o el id del mensaje de RabbitMQ.
    source varchar(20),
    correlation_id varchar(200),
    ip_address varchar(100),
    user_agent varchar(500),
    request_method varchar(20),
    request_uri varchar(500)
);

CREATE INDEX idx_audit_revision_timestamp ON audit_revision (timestamp);
CREATE INDEX idx_audit_revision_username ON audit_revision (username);
CREATE INDEX idx_audit_revision_correlation_id ON audit_revision (correlation_id)
    WHERE correlation_id IS NOT NULL;

-- Las siete gemelas.
--
-- Todas las columnas de negocio son nullable y no llevan CHECK: una fila de borrado escribe nulos,
-- y un NOT NULL aqui convertiria un DELETE en un fallo de ejecucion. Tampoco llevan UNIQUE -code se
-- repite por diseno, una vez por revision- ni clave ajena a la tabla base, porque una fila de
-- historial tiene que sobrevivir a la fila que describe. La unica clave ajena es rev.
--
-- La clave primaria es (rev, id), que es lo que espera Envers, pero la consulta que se hace de
-- verdad -"historial de esta entidad"- filtra por id; de ahi el indice (id, rev).

CREATE TABLE material_aud (
    id uuid NOT NULL,
    rev integer NOT NULL,
    revtype smallint,
    code varchar(64),
    name varchar(255),
    unit_of_measure varchar(32),
    minimum_stock_level numeric(19, 6),
    active boolean,
    CONSTRAINT pk_material_aud PRIMARY KEY (rev, id),
    CONSTRAINT fk_material_aud_revision FOREIGN KEY (rev) REFERENCES audit_revision (id)
);

CREATE INDEX idx_material_aud_id_rev ON material_aud (id, rev);

CREATE TABLE supplier_aud (
    id uuid NOT NULL,
    rev integer NOT NULL,
    revtype smallint,
    code varchar(64),
    name varchar(255),
    active boolean,
    CONSTRAINT pk_supplier_aud PRIMARY KEY (rev, id),
    CONSTRAINT fk_supplier_aud_revision FOREIGN KEY (rev) REFERENCES audit_revision (id)
);

CREATE INDEX idx_supplier_aud_id_rev ON supplier_aud (id, rev);

CREATE TABLE warehouse_aud (
    id uuid NOT NULL,
    rev integer NOT NULL,
    revtype smallint,
    code varchar(64),
    name varchar(255),
    active boolean,
    CONSTRAINT pk_warehouse_aud PRIMARY KEY (rev, id),
    CONSTRAINT fk_warehouse_aud_revision FOREIGN KEY (rev) REFERENCES audit_revision (id)
);

CREATE INDEX idx_warehouse_aud_id_rev ON warehouse_aud (id, rev);

-- Ojo con esta: los cambios que llegan por evento de datos maestros NO dejan revision, porque
-- ProjectRepository.upsertFromMasterData y deactivateFromMasterData son SQL nativo a proposito -la
-- marca de agua de secuencia se comprueba dentro del where de la propia sentencia-. project_aud
-- recoge solo el camino REST. Ver docs/07-auditing.md.
CREATE TABLE project_aud (
    id uuid NOT NULL,
    rev integer NOT NULL,
    revtype smallint,
    code varchar(64),
    name varchar(255),
    active boolean,
    source_service varchar(100),
    source_entity_id varchar(100),
    source_sequence_number bigint,
    CONSTRAINT pk_project_aud PRIMARY KEY (rev, id),
    CONSTRAINT fk_project_aud_revision FOREIGN KEY (rev) REFERENCES audit_revision (id)
);

CREATE INDEX idx_project_aud_id_rev ON project_aud (id, rev);

-- Sin tabla de coleccion para components: la relacion es bidireccional con mappedBy, asi que Envers
-- la guarda solo en el lado "muchos" y el despiece de una revision se reconstruye desde
-- assembly_component_aud.assembly_id.
CREATE TABLE assembly_aud (
    id uuid NOT NULL,
    rev integer NOT NULL,
    revtype smallint,
    code varchar(64),
    name varchar(255),
    active boolean,
    CONSTRAINT pk_assembly_aud PRIMARY KEY (rev, id),
    CONSTRAINT fk_assembly_aud_revision FOREIGN KEY (rev) REFERENCES audit_revision (id)
);

CREATE INDEX idx_assembly_aud_id_rev ON assembly_aud (id, rev);

CREATE TABLE assembly_component_aud (
    id uuid NOT NULL,
    rev integer NOT NULL,
    revtype smallint,
    assembly_id uuid,
    material_id uuid,
    quantity numeric(19, 6),
    CONSTRAINT pk_assembly_component_aud PRIMARY KEY (rev, id),
    CONSTRAINT fk_assembly_component_aud_revision FOREIGN KEY (rev) REFERENCES audit_revision (id)
);

CREATE INDEX idx_assembly_component_aud_id_rev ON assembly_component_aud (id, rev);

CREATE TABLE reservation_aud (
    id uuid NOT NULL,
    rev integer NOT NULL,
    revtype smallint,
    material_id uuid,
    warehouse_id uuid,
    project_id uuid,
    quantity numeric(19, 6),
    -- El MISMO tipo enum que la tabla base, no varchar: la entidad mapea status con
    -- @JdbcTypeCode(SqlTypes.NAMED_ENUM) y Envers copia ese mapeo a la gemela, asi que con varchar
    -- aqui ddl-auto: validate no encuentra el tipo que espera y la aplicacion no arranca. Nullable,
    -- al contrario que en reservation, porque una fila de borrado escribe nulos.
    status reservation_status,
    reserved_at timestamptz,
    released_at timestamptz,
    CONSTRAINT pk_reservation_aud PRIMARY KEY (rev, id),
    CONSTRAINT fk_reservation_aud_revision FOREIGN KEY (rev) REFERENCES audit_revision (id)
);

CREATE INDEX idx_reservation_aud_id_rev ON reservation_aud (id, rev);

-- Revision de partida: foto del estado en el momento de instalar el historial.
--
-- NO es un evento de creacion, y por eso source = 'BASELINE': estas filas no dicen que alguien
-- creara nada ahora, dicen "asi estaba esto cuando empezo a guardarse el historial". Sin ellas, el
-- primer cambio de una fila anterior a Envers dejaria una revision MOD con el estado nuevo y sin
-- predecesor, y el estado previo se perderia para siempre.
--
-- revtype = 0 es ADD, que es lo que Envers espera leer como primera revision de una entidad.
INSERT INTO audit_revision (id, timestamp, username, source)
VALUES (1, (EXTRACT(EPOCH FROM now()) * 1000)::bigint, 'system', 'BASELINE');

INSERT INTO material_aud (id, rev, revtype, code, name, unit_of_measure, minimum_stock_level, active)
SELECT id, 1, 0, code, name, unit_of_measure, minimum_stock_level, active FROM material;

INSERT INTO supplier_aud (id, rev, revtype, code, name, active)
SELECT id, 1, 0, code, name, active FROM supplier;

INSERT INTO warehouse_aud (id, rev, revtype, code, name, active)
SELECT id, 1, 0, code, name, active FROM warehouse;

INSERT INTO project_aud (id, rev, revtype, code, name, active, source_service, source_entity_id, source_sequence_number)
SELECT id, 1, 0, code, name, active, source_service, source_entity_id, source_sequence_number FROM project;

INSERT INTO assembly_aud (id, rev, revtype, code, name, active)
SELECT id, 1, 0, code, name, active FROM assembly;

INSERT INTO assembly_component_aud (id, rev, revtype, assembly_id, material_id, quantity)
SELECT id, 1, 0, assembly_id, material_id, quantity FROM assembly_component;

INSERT INTO reservation_aud (id, rev, revtype, material_id, warehouse_id, project_id, quantity, status, reserved_at, released_at)
SELECT id, 1, 0, material_id, warehouse_id, project_id, quantity, status, reserved_at, released_at FROM reservation;
