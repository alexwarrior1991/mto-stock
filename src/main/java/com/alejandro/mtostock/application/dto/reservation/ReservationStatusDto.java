package com.alejandro.mtostock.application.dto.reservation;

/**
 * API enum describing whether a reservation still reduces available stock.
 */
public enum ReservationStatusDto {

    ACTIVE,
    RELEASED,
    CONSUMED,
    CANCELLED

}