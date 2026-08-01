CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TYPE stock_movement_type AS ENUM (
    'ENTRY',
    'OUTPUT',
    'POSITIVE_ADJUSTMENT',
    'NEGATIVE_ADJUSTMENT',
    'INCOMING_TRANSFER',
    'OUTGOING_TRANSFER'
);

CREATE TYPE reservation_status AS ENUM (
    'ACTIVE',
    'RELEASED',
    'CANCELLED'
);

CREATE TABLE material (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code varchar(64) NOT NULL,
    name varchar(255) NOT NULL,
    unit_of_measure varchar(32) NOT NULL,
    minimum_stock_level numeric(19, 6) NOT NULL DEFAULT 0,
    active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_material_code UNIQUE (code),
    CONSTRAINT chk_material_code_not_blank CHECK (btrim(code) <> ''),
    CONSTRAINT chk_material_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT chk_material_unit_of_measure_not_blank CHECK (btrim(unit_of_measure) <> ''),
    CONSTRAINT chk_material_minimum_stock_level_non_negative CHECK (minimum_stock_level >= 0)
);

CREATE TABLE supplier (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code varchar(64) NOT NULL,
    name varchar(255) NOT NULL,
    active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_supplier_code UNIQUE (code),
    CONSTRAINT chk_supplier_code_not_blank CHECK (btrim(code) <> ''),
    CONSTRAINT chk_supplier_name_not_blank CHECK (btrim(name) <> '')
);

CREATE TABLE warehouse (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code varchar(64) NOT NULL,
    name varchar(255) NOT NULL,
    active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_warehouse_code UNIQUE (code),
    CONSTRAINT chk_warehouse_code_not_blank CHECK (btrim(code) <> ''),
    CONSTRAINT chk_warehouse_name_not_blank CHECK (btrim(name) <> '')
);

CREATE TABLE project (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code varchar(64) NOT NULL,
    name varchar(255) NOT NULL,
    active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_project_code UNIQUE (code),
    CONSTRAINT chk_project_code_not_blank CHECK (btrim(code) <> ''),
    CONSTRAINT chk_project_name_not_blank CHECK (btrim(name) <> '')
);

CREATE TABLE assembly (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code varchar(64) NOT NULL,
    name varchar(255) NOT NULL,
    active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_assembly_code UNIQUE (code),
    CONSTRAINT chk_assembly_code_not_blank CHECK (btrim(code) <> ''),
    CONSTRAINT chk_assembly_name_not_blank CHECK (btrim(name) <> '')
);

CREATE TABLE assembly_component (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    assembly_id uuid NOT NULL,
    material_id uuid NOT NULL,
    quantity numeric(19, 6) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_assembly_component_assembly FOREIGN KEY (assembly_id) REFERENCES assembly (id) ON DELETE RESTRICT,
    CONSTRAINT fk_assembly_component_material FOREIGN KEY (material_id) REFERENCES material (id) ON DELETE RESTRICT,
    CONSTRAINT uq_assembly_component_assembly_material UNIQUE (assembly_id, material_id),
    CONSTRAINT chk_assembly_component_quantity_positive CHECK (quantity > 0)
);

CREATE TABLE reservation (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    material_id uuid NOT NULL,
    warehouse_id uuid NOT NULL,
    project_id uuid NOT NULL,
    quantity numeric(19, 6) NOT NULL,
    status reservation_status NOT NULL DEFAULT 'ACTIVE',
    reserved_at timestamptz NOT NULL DEFAULT now(),
    released_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_reservation_material FOREIGN KEY (material_id) REFERENCES material (id) ON DELETE RESTRICT,
    CONSTRAINT fk_reservation_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse (id) ON DELETE RESTRICT,
    CONSTRAINT fk_reservation_project FOREIGN KEY (project_id) REFERENCES project (id) ON DELETE RESTRICT,
    CONSTRAINT chk_reservation_quantity_positive CHECK (quantity > 0),
    CONSTRAINT chk_reservation_release_timestamp_matches_status CHECK (
        (status = 'ACTIVE' AND released_at IS NULL)
        OR (status IN ('RELEASED', 'CANCELLED') AND released_at IS NOT NULL)
    )
);

CREATE TABLE stock_movement (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    material_id uuid NOT NULL,
    warehouse_id uuid NOT NULL,
    type stock_movement_type NOT NULL,
    quantity numeric(19, 6) NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    supplier_id uuid,
    project_id uuid,
    reservation_id uuid,
    related_movement_id uuid,
    external_reference varchar(128),
    notes text,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_stock_movement_material FOREIGN KEY (material_id) REFERENCES material (id) ON DELETE RESTRICT,
    CONSTRAINT fk_stock_movement_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse (id) ON DELETE RESTRICT,
    CONSTRAINT fk_stock_movement_supplier FOREIGN KEY (supplier_id) REFERENCES supplier (id) ON DELETE RESTRICT,
    CONSTRAINT fk_stock_movement_project FOREIGN KEY (project_id) REFERENCES project (id) ON DELETE RESTRICT,
    CONSTRAINT fk_stock_movement_reservation FOREIGN KEY (reservation_id) REFERENCES reservation (id) ON DELETE RESTRICT,
    CONSTRAINT fk_stock_movement_related_movement FOREIGN KEY (related_movement_id) REFERENCES stock_movement (id) ON DELETE RESTRICT,
    CONSTRAINT chk_stock_movement_quantity_positive CHECK (quantity > 0),
    CONSTRAINT chk_stock_movement_external_reference_not_blank CHECK (external_reference IS NULL OR btrim(external_reference) <> ''),
    CONSTRAINT chk_stock_movement_not_related_to_self CHECK (related_movement_id IS NULL OR related_movement_id <> id)
);

CREATE INDEX idx_material_active ON material (active);
CREATE INDEX idx_supplier_active ON supplier (active);
CREATE INDEX idx_warehouse_active ON warehouse (active);
CREATE INDEX idx_project_active ON project (active);
CREATE INDEX idx_assembly_active ON assembly (active);

CREATE INDEX idx_assembly_component_assembly_id ON assembly_component (assembly_id);
CREATE INDEX idx_assembly_component_material_id ON assembly_component (material_id);

CREATE INDEX idx_reservation_active_material_warehouse
    ON reservation (material_id, warehouse_id)
    WHERE status = 'ACTIVE';
CREATE INDEX idx_reservation_project_status ON reservation (project_id, status);
CREATE INDEX idx_reservation_warehouse_status ON reservation (warehouse_id, status);

CREATE INDEX idx_stock_movement_material_warehouse_occurred_at
    ON stock_movement (material_id, warehouse_id, occurred_at DESC);
CREATE INDEX idx_stock_movement_warehouse_occurred_at
    ON stock_movement (warehouse_id, occurred_at DESC);
CREATE INDEX idx_stock_movement_type_occurred_at
    ON stock_movement (type, occurred_at DESC);
CREATE INDEX idx_stock_movement_supplier_id ON stock_movement (supplier_id) WHERE supplier_id IS NOT NULL;
CREATE INDEX idx_stock_movement_project_id ON stock_movement (project_id) WHERE project_id IS NOT NULL;
CREATE INDEX idx_stock_movement_reservation_id ON stock_movement (reservation_id) WHERE reservation_id IS NOT NULL;
CREATE INDEX idx_stock_movement_related_movement_id ON stock_movement (related_movement_id) WHERE related_movement_id IS NOT NULL;