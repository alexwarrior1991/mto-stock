-- Origen de los proyectos que llegan de mto-configuration como datos maestros.
--
-- Un proyecto de mto-stock puede nacer de dos sitios: creado a mano por la API, o sincronizado
-- desde un paquete de ejecucion. Estas columnas dicen cual es cual, y son la clave por la que se
-- reconoce el mismo paquete en entregas sucesivas: los ids de mto-configuration son numericos y no
-- caben en 'code', que ademas es un identificador de negocio que la gente lee y escribe.
--
-- Van a NULL en los proyectos creados a mano. PostgreSQL considera distintos dos NULL en un indice
-- unico, asi que la restriccion no molesta a esas filas por muchas que haya.
ALTER TABLE project
    ADD COLUMN source_service varchar(100),
    ADD COLUMN source_entity_id varchar(100);

ALTER TABLE project
    ADD CONSTRAINT uq_project_source UNIQUE (source_service, source_entity_id),
    -- O ninguna de las dos o las dos: una sola no identifica nada, y una fila a medio rellenar
    -- volveria a insertarse en la siguiente entrega en lugar de actualizarse.
    ADD CONSTRAINT chk_project_source_columns_together CHECK (
        (source_service IS NULL AND source_entity_id IS NULL)
        OR (source_service IS NOT NULL AND source_entity_id IS NOT NULL)
    );
