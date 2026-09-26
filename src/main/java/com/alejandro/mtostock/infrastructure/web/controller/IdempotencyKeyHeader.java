package com.alejandro.mtostock.infrastructure.web.controller;

/**
 * The header that makes a reservation or an output safe to repeat.
 */
final class IdempotencyKeyHeader {

    static final String NAME = "Idempotency-Key";

    static final String DESCRIPTION = """
            Optional, 1 to 255 visible ASCII characters, chosen by the client. A retry with the same key \
            and the same body writes nothing and answers with what the first request created, as it is \
            now; the same key with a different body is 409 IDEM-001. The date does not count: a retry \
            may carry another one, and the first request's stands. Keys belong to the authenticated \
            caller and to the operation, and are forgotten 30 days after their first use (by default): \
            a retry after that is a new request.""";

    private IdempotencyKeyHeader() {
    }
}
