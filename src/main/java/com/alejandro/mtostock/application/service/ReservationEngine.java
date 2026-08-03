package com.alejandro.mtostock.application.service;

import com.alejandro.mtostock.infrastructure.persistence.entity.Reservation;

import java.util.UUID;

/**
 * Domain service responsible only for reservation lifecycle business rules.
 */
public interface ReservationEngine {

    Reservation create(Reservation reservation);

    Reservation update(UUID id, Reservation reservation);

    Reservation cancel(UUID id);

    Reservation release(UUID id);

    Reservation consume(UUID id);
}