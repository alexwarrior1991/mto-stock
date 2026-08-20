package com.alejandro.mtostock.infrastructure.persistence.entity;

/**
 * Defines whether a reservation currently reduces available material stock.
 */
public enum ReservationStatus {

    ACTIVE,
    RELEASED,
    CONSUMED,
    CANCELLED

}