package com.alejandro.mtostock.infrastructure.web.controller;

/**
 * The header that makes a reservation or an output safe to repeat.
 */
final class IdempotencyKeyHeader {

    static final String NAME = "Idempotency-Key";

    static final String DESCRIPTION = """
            Optional, 1 to 255 visible ASCII characters, chosen by the client. A retry with the same key \
            and the same body writes nothing and answers with what the first request created, as it is \
            now; the same key with a different body is 409 IDEM-001. Keys belong to the authenticated \
            caller and to the operation.""";

    private IdempotencyKeyHeader() {
    }
}
