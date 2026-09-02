-- Marca de agua del ultimo cambio aplicado a cada proyecto sincronizado.
--
-- El inbox impide aplicar DOS VECES el mismo mensaje, no aplicar uno VIEJO despues de uno nuevo, que
-- es un problema distinto: si el evento A de un paquete de ejecucion falla y se reprograma, el
-- evento B -posterior- puede llegar antes, y al reintentarse A el nombre viejo pisaria al nuevo. Un
-- UPDATE retrasado detras de un DELETE llegaba incluso a reactivar el proyecto.
--
-- mto-configuration numera cada mensaje con una secuencia de PostgreSQL, global y creciente, y lo
-- publica en la cabecera 'sequenceNumber'. Como es creciente para toda la tabla, tambien lo es para
-- los mensajes de un mismo agregado, asi que basta guardar el ultimo numero aplicado y descartar lo
-- que venga por debajo.
--
-- Queda a NULL en los proyectos creados a mano, que no se sincronizan con nadie.
ALTER TABLE project
    ADD COLUMN source_sequence_number bigint;

ALTER TABLE project
    ADD CONSTRAINT chk_project_source_sequence_requires_source CHECK (
        source_sequence_number IS NULL OR source_service IS NOT NULL
    );
