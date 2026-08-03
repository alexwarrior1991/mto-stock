package com.alejandro.mtostock.application.exception;

import java.util.UUID;

/**
 * Raised when an aggregate required by a business use case does not exist.
 */
public class NotFoundException extends BusinessException {

    private final String aggregate;
    private final UUID id;

    public NotFoundException(String aggregate, UUID id) {
        super("%s with id %s was not found".formatted(aggregate, id));
        this.aggregate = aggregate;
        this.id = id;
    }

    public NotFoundException(String message) {
        super(message);
        this.aggregate = null;
        this.id = null;
    }

    public String getAggregate() {
        return aggregate;
    }

    public UUID getId() {
        return id;
    }
}