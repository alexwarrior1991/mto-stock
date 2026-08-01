ALTER TABLE material
    ADD COLUMN created_by varchar(100) NOT NULL DEFAULT 'system',
    ADD COLUMN updated_by varchar(100) NOT NULL DEFAULT 'system';

ALTER TABLE supplier
    ADD COLUMN created_by varchar(100) NOT NULL DEFAULT 'system',
    ADD COLUMN updated_by varchar(100) NOT NULL DEFAULT 'system';

ALTER TABLE warehouse
    ADD COLUMN created_by varchar(100) NOT NULL DEFAULT 'system',
    ADD COLUMN updated_by varchar(100) NOT NULL DEFAULT 'system';

ALTER TABLE project
    ADD COLUMN created_by varchar(100) NOT NULL DEFAULT 'system',
    ADD COLUMN updated_by varchar(100) NOT NULL DEFAULT 'system';

ALTER TABLE assembly
    ADD COLUMN created_by varchar(100) NOT NULL DEFAULT 'system',
    ADD COLUMN updated_by varchar(100) NOT NULL DEFAULT 'system';

ALTER TABLE assembly_component
    ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN created_by varchar(100) NOT NULL DEFAULT 'system',
    ADD COLUMN updated_by varchar(100) NOT NULL DEFAULT 'system';

ALTER TABLE reservation
    ADD COLUMN created_by varchar(100) NOT NULL DEFAULT 'system',
    ADD COLUMN updated_by varchar(100) NOT NULL DEFAULT 'system';

ALTER TABLE stock_movement
    ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN created_by varchar(100) NOT NULL DEFAULT 'system',
    ADD COLUMN updated_by varchar(100) NOT NULL DEFAULT 'system';