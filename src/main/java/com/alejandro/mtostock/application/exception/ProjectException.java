package com.alejandro.mtostock.application.exception;

/**
 * Raised when a project operation violates inventory rules, such as editing through the API a project
 * that is synchronized from master data.
 */
public class ProjectException extends BusinessException {

    public ProjectException(String message) {
        super(message);
    }
}
