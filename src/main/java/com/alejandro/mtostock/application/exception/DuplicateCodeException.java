package com.alejandro.mtostock.application.exception;

/**
 * Raised when a unique business code is already assigned to another aggregate.
 */
public class DuplicateCodeException extends BusinessException {

    private final String aggregate;
    private final String code;

    public DuplicateCodeException(String aggregate, String code) {
        super("%s code '%s' is already in use".formatted(aggregate, code));
        this.aggregate = aggregate;
        this.code = code;
    }

    public String getAggregate() {
        return aggregate;
    }

    public String getCode() {
        return code;
    }
}