package it.its.cinema.bookingservice.client.dto;

import java.math.BigDecimal;

/**
 * La risposta di payment-service.
 *
 * Solo i campi che ci servono: Jackson ignora quelli che non dichiariamo
 * (createdAt, refundedAt, motivo). E' voluto — un consumatore non deve
 * rompersi perche' chi produce ha aggiunto un campo, ed e' meta' di cio'
 * che rende sopportabile avere due copie dello stesso contratto.
 */
public record PaymentJson(Long id, String sagaId, BigDecimal amount, String stato) {
}
