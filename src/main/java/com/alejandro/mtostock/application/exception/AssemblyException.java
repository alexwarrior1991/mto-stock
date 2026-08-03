package com.alejandro.mtostock.application.exception;

/**
 * Raised when a bill-of-materials or virtual assembly rule is violated.
 */
public class AssemblyException extends BusinessException {

    public AssemblyException(String message) {
        super(message);
    }
}