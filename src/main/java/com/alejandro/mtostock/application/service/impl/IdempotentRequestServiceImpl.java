package com.alejandro.mtostock.application.service.impl;

import com.alejandro.mtostock.application.dto.common.IdempotencyIgnored;
import com.alejandro.mtostock.application.exception.IdempotencyKeyConflictException;
import com.alejandro.mtostock.application.exception.ValidationException;
import com.alejandro.mtostock.application.service.IdempotentRequestService;
import com.alejandro.mtostock.infrastructure.persistence.entity.IdempotentOperation;
import com.alejandro.mtostock.infrastructure.persistence.entity.IdempotentRequest;
import com.alejandro.mtostock.infrastructure.persistence.repository.IdempotentRequestRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.AuditorAware;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Claims the {@code Idempotency-Key} of a write and records what it created.
 */
@Service
@RequiredArgsConstructor
class IdempotentRequestServiceImpl implements IdempotentRequestService {

    private static final Logger LOGGER = LoggerFactory.getLogger(IdempotentRequestServiceImpl.class);

    /** De 1 a 255 caracteres ASCII visibles: cabe un UUID o una clave compuesta, y nada que una cabecera no lleve bien. */
    private static final Pattern KEY_FORMAT = Pattern.compile("[\\x21-\\x7E]{1,255}");
    private static final String SYSTEM_ACTOR = "system";
    private static final int MAX_ACTOR_LENGTH = 100;
    static final int PURGE_BATCH_SIZE = 1_000;

    private final IdempotentRequestRepository idempotentRequestRepository;
    private final AuditorAware<String> auditorAware;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<UUID> claim(IdempotentOperation operation, String idempotencyKey, Record request) {
        if (idempotencyKey == null) {
            return Optional.empty();
        }
        if (!KEY_FORMAT.matcher(idempotencyKey).matches()) {
            throw new ValidationException("Idempotency-Key must be 1 to 255 visible ASCII characters");
        }
        String caller = caller();
        String fingerprint = fingerprint(request);
        Optional<IdempotentRequest> stored = claimOrRead(operation, idempotencyKey, fingerprint, caller);
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        IdempotentRequest previous = stored.get();
        if (!previous.getRequestHash().equals(fingerprint)) {
            throw new IdempotencyKeyConflictException(idempotencyKey);
        }
        if (previous.getResourceId() == null) {
            throw new IllegalStateException("Idempotency key %s of %s for %s was committed without the resource it created"
                    .formatted(idempotencyKey, caller, operation));
        }
        LOGGER.info("{} with idempotency key {} was already applied as {}: returning it instead of writing again",
                operation, idempotencyKey, previous.getResourceId());
        return Optional.of(previous.getResourceId());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(IdempotentOperation operation, String idempotencyKey, UUID resourceId) {
        if (idempotencyKey == null) {
            return;
        }
        if (idempotentRequestRepository.recordResource(operation.name(), idempotencyKey, caller(), resourceId) != 1) {
            throw new IllegalStateException("Idempotency key %s for %s was not claimed by this write"
                    .formatted(idempotencyKey, operation));
        }
    }

    /**
     * Sin transacción a propósito: cada lote se borra en la suya ({@link IdempotentRequestRepository#deleteClaimedBefore}),
     * para que una purga grande no retenga miles de filas bloqueadas hasta el final.
     */
    @Override
    public int purgeClaimedBefore(Instant cutoff) {
        int purged = 0;
        int batch;
        do {
            batch = idempotentRequestRepository.deleteClaimedBefore(cutoff, PURGE_BATCH_SIZE);
            purged += batch;
        } while (batch == PURGE_BATCH_SIZE);
        if (purged > 0) {
            LOGGER.info("Forgot {} idempotency keys first used before {}: a retry with one of them is a new request", purged, cutoff);
        }
        return purged;
    }

    /**
     * Reclama la clave, o lee la fila de quien la reclamó antes.
     *
     * <p>Una reclamación que no inserta nada ha esperado a que terminara la transacción que insertó la
     * fila, así que la fila está confirmada, y con lo que creó: esa transacción no confirma sin
     * apuntarlo. Solo puede faltar al leerla si la purga la ha borrado justo entre las dos sentencias,
     * porque había caducado; entonces la clave vuelve a estar libre y se reclama otra vez, y esta
     * petición es la primera.</p>
     *
     * @return vacío si esta petición ha reclamado la clave; la fila guardada si no
     */
    private Optional<IdempotentRequest> claimOrRead(IdempotentOperation operation, String idempotencyKey, String fingerprint,
                                                    String caller) {
        for (int attempt = 1; ; attempt++) {
            if (idempotentRequestRepository.claim(operation.name(), idempotencyKey, fingerprint, caller) == 1) {
                return Optional.empty();
            }
            Optional<IdempotentRequest> stored = idempotentRequestRepository
                    .findByOperationAndCreatedByAndIdempotencyKey(operation, caller, idempotencyKey);
            if (stored.isPresent()) {
                return stored;
            }
            if (attempt == 2) {
                throw new IllegalStateException("Idempotency key %s of %s for %s was claimed but cannot be read"
                        .formatted(idempotencyKey, caller, operation));
            }
        }
    }

    /**
     * SHA-256 de los componentes con valor del cuerpo, cada uno con su nombre y en su orden.
     *
     * <p>Un componente vacío no cuenta, así que un campo opcional que se añada al contrato no cambia
     * la huella de quien no lo manda. Tampoco cuenta uno marcado con {@link IdempotencyIgnored}, la
     * fecha: un reintento con la hora de cada intento es la misma petición. Un decimal cuenta por su
     * valor: {@code 2} y {@code 2.000000} son la misma cantidad. Cada trozo va precedido de su
     * longitud, para que dos cuerpos distintos no den nunca la misma secuencia de bytes.</p>
     */
    static String fingerprint(Record request) {
        MessageDigest digest = sha256();
        for (RecordComponent component : request.getClass().getRecordComponents()) {
            if (component.isAnnotationPresent(IdempotencyIgnored.class)) {
                continue;
            }
            Object value = valueOf(request, component);
            if (value != null) {
                update(digest, component.getName());
                update(digest, value instanceof BigDecimal decimal ? decimal.stripTrailingZeros().toPlainString() : value.toString());
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static Object valueOf(Record request, RecordComponent component) {
        try {
            return component.getAccessor().invoke(request);
        } catch (IllegalAccessException | InvocationTargetException exception) {
            throw new IllegalStateException("Cannot read " + component.getName() + " of " + request.getClass().getSimpleName(), exception);
        }
    }

    private static void update(MessageDigest digest, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    /** Quien manda la petición, resuelto como las columnas de auditoría: la clave es suya. */
    private String caller() {
        return auditorAware.getCurrentAuditor()
                .map(String::trim)
                .filter(actor -> !actor.isBlank())
                .map(actor -> actor.length() > MAX_ACTOR_LENGTH ? actor.substring(0, MAX_ACTOR_LENGTH) : actor)
                .orElse(SYSTEM_ACTOR);
    }
}
