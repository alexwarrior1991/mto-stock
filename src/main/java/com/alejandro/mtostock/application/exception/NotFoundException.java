package com.alejandro.mtostock.application.exception;

import java.util.UUID;

/**
 * Raised when an aggregate required by a business use case does not exist.
 */
public class NotFoundException extends BusinessException {

    public NotFoundException(String aggregate, UUID id) {
        super("%s with id %s was not found".formatted(aggregate, id));
    }

    public NotFoundException(String message) {
        super(message);
    }
}