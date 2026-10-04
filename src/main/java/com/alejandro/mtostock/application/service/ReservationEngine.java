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

    /**
     * Deja consumida una reserva activa y baja su cantidad del físico y de lo reservado. No escribe el
     * libro: la salida la escribe quien la llama, en la misma transacción
     * ({@code StockMovementService.registerOutput} con la reserva, o
     * {@code registerReservationConsumption}). Llamarla sola deja un físico que el libro no cuenta.
     */
    Reservation consume(UUID id);
}