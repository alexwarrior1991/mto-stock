package com.alejandro.mtostock.application.service;

import com.alejandro.mtostock.application.dto.stock.StockMovementTransferRequest;
import com.alejandro.mtostock.infrastructure.persistence.entity.StockMovement;

import java.util.List;

/**
 * Domain service responsible only for atomic warehouse transfer movements.
 */
public interface TransferService {

    List<StockMovement> transfer(StockMovementTransferRequest request);
}