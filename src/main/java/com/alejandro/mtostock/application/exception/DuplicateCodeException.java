package com.alejandro.mtostock.application.exception;

/**
 * Raised when a unique business code is already assigned to another aggregate.
 */
public class DuplicateCodeException extends BusinessException {

    public DuplicateCodeException(String aggregate, String code) {
        super("%s code '%s' is already in use".formatted(aggregate, code));
    }
}