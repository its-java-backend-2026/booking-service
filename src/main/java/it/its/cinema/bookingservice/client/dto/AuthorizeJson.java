package it.its.cinema.bookingservice.client.dto;

import java.math.BigDecimal;

/**
 * PASSO 8.1 — il corpo di POST /payments/authorize.
 *
 * E' la copia locale di AuthorizeRequest di payment-service: stesso JSON,
 * due record in due repository. E' la duplicazione voluta del passo 6.8 —
 * i nomi dei campi devono combaciare, e nessun compilatore lo verifica.
 */
public record AuthorizeJson(String sagaId, Long bookingId, BigDecimal amount) {
}
