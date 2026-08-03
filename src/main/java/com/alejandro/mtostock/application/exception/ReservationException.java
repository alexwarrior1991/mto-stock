package com.alejandro.mtostock.application.exception;

/**
 * Raised when a reservation lifecycle transition violates business rules.
 */
public class ReservationException extends BusinessException {

    public ReservationException(String message) {
        super(message);
    }
}