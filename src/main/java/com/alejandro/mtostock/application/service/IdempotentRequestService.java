package com.alejandro.mtostock.application.service;

import com.alejandro.mtostock.infrastructure.persistence.entity.IdempotentOperation;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Writes a client can repeat without duplicating them: a reservation and an output sent with an
 * {@code Idempotency-Key}.
 *
 * <p>La escritura reclama su clave al empezar, dentro de su propia transacción, y apunta lo que creó
 * al terminar. Si la clave ya se había usado con el mismo cuerpo, no se repite nada: la escritura
 * devuelve lo que creó la primera vez, tal como está ahora, sin volver a validar existencias ni tocar
 * el saldo. Si se usó con otro cuerpo, es un error del cliente. Sin clave, todo sigue como siempre.</p>
 *
 * <p>{@link #claim} y {@link #complete} corren en la transacción de la escritura y fallan sin ella:
 * reclamar en una transacción aparte dejaría la clave apuntada aunque la escritura revirtiera, y el
 * reintento encontraría una petición que no llegó a crear nada.</p>
 *
 * <p>Una clave no se recuerda para siempre: {@link #purgeClaimedBefore} olvida las que se usaron por
 * primera vez hace más de lo que dice {@code app.idempotency.retention}, y un reintento con una de
 * ellas es ya una petición nueva. Es el contrato con los clientes: el que reintenta tiene que hacerlo
 * dentro de ese plazo.</p>
 */
public interface IdempotentRequestService {

    /**
     * Reclama la clave para esta petición.
     *
     * @param request el cuerpo de la petición; su huella es cada componente con valor salvo la fecha
     *                ({@link com.alejandro.mtostock.application.dto.common.IdempotencyIgnored}), y un
     *                decimal cuenta por su valor, no por su escala
     * @return vacío si hay que ejecutar la escritura (la clave es nueva, o no hay clave); el id de lo
     *         que se creó si la petición ya se aplicó
     * @throws com.alejandro.mtostock.application.exception.ValidationException si la clave no son de
     *         1 a 255 caracteres ASCII visibles
     * @throws com.alejandro.mtostock.application.exception.IdempotencyKeyConflictException si la clave
     *         ya se usó con otro cuerpo
     */
    Optional<UUID> claim(IdempotentOperation operation, String idempotencyKey, Record request);

    /** Apunta lo que creó la escritura que reclamó la clave. Sin clave, no hace nada. */
    void complete(IdempotentOperation operation, String idempotencyKey, UUID resourceId);

    /**
     * Olvida las claves usadas por primera vez antes de {@code cutoff}, por lotes y cada lote en su
     * propia transacción. Lo que crearon no se toca: solo deja de reconocerse el reintento.
     *
     * @return cuántas claves se han olvidado
     */
    int purgeClaimedBefore(Instant cutoff);
}
