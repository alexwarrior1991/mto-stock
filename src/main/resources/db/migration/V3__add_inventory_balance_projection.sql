ALTER TYPE reservation_status ADD VALUE IF NOT EXISTS 'CONSUMED';

ALTER TABLE reservation
    DROP CONSTRAINT chk_reservation_release_timestamp_matches_status,
    ADD CONSTRAINT chk_reservation_release_timestamp_matches_status CHECK (
        (status = 'ACTIVE' AND released_at IS NULL)
        OR (status <> 'ACTIVE' AND released_at IS NOT NULL)
    );

CREATE TABLE inventory_balance (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    material_id uuid NOT NULL,
    warehouse_id uuid NOT NULL,
    physical_quantity numeric(19, 6) NOT NULL DEFAULT 0,
    reserved_quantity numeric(19, 6) NOT NULL DEFAULT 0,
    available_quantity numeric(19, 6) NOT NULL DEFAULT 0,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_by varchar(100) NOT NULL DEFAULT 'system',
    updated_by varchar(100) NOT NULL DEFAULT 'system',
    CONSTRAINT fk_inventory_balance_material FOREIGN KEY (material_id) REFERENCES material (id) ON DELETE RESTRICT,
    CONSTRAINT fk_inventory_balance_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse (id) ON DELETE RESTRICT,
    CONSTRAINT uq_inventory_balance_material_warehouse UNIQUE (material_id, warehouse_id),
    CONSTRAINT chk_inventory_balance_physical_quantity_non_negative CHECK (physical_quantity >= 0),
    CONSTRAINT chk_inventory_balance_reserved_quantity_non_negative CHECK (reserved_quantity >= 0),
    CONSTRAINT chk_inventory_balance_available_quantity_non_negative CHECK (available_quantity >= 0),
    CONSTRAINT chk_inventory_balance_available_matches_quantities CHECK (available_quantity = physical_quantity - reserved_quantity)
);

CREATE INDEX idx_inventory_balance_material_id ON inventory_balance (material_id);
CREATE INDEX idx_inventory_balance_warehouse_id ON inventory_balance (warehouse_id);
CREATE INDEX idx_inventory_balance_material_warehouse ON inventory_balance (material_id, warehouse_id);

WITH movement_balance AS (
    SELECT
        material_id,
        warehouse_id,
        COALESCE(SUM(
            CASE
                WHEN type IN ('ENTRY', 'POSITIVE_ADJUSTMENT', 'INCOMING_TRANSFER') THEN quantity
                ELSE -quantity
            END
        ), 0) AS physical_quantity
    FROM stock_movement
    GROUP BY material_id, warehouse_id
), reservation_balance AS (
    SELECT
        material_id,
        warehouse_id,
        COALESCE(SUM(quantity), 0) AS reserved_quantity
    FROM reservation
    WHERE status = 'ACTIVE'
    GROUP BY material_id, warehouse_id
), balance_keys AS (
    SELECT material_id, warehouse_id FROM movement_balance
    UNION
    SELECT material_id, warehouse_id FROM reservation_balance
)
INSERT INTO inventory_balance (
    id,
    material_id,
    warehouse_id,
    physical_quantity,
    reserved_quantity,
    available_quantity,
    version,
    created_at,
    updated_at,
    created_by,
    updated_by
)
SELECT
    gen_random_uuid(),
    balance_keys.material_id,
    balance_keys.warehouse_id,
    COALESCE(movement_balance.physical_quantity, 0),
    COALESCE(reservation_balance.reserved_quantity, 0),
    COALESCE(movement_balance.physical_quantity, 0) - COALESCE(reservation_balance.reserved_quantity, 0),
    0,
    now(),
    now(),
    'system',
    'system'
FROM balance_keys
LEFT JOIN movement_balance
       ON movement_balance.material_id = balance_keys.material_id
      AND movement_balance.warehouse_id = balance_keys.warehouse_id
LEFT JOIN reservation_balance
       ON reservation_balance.material_id = balance_keys.material_id
      AND reservation_balance.warehouse_id = balance_keys.warehouse_id;