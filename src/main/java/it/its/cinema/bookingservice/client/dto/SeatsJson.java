package it.its.cinema.bookingservice.client.dto;

/**
 * PASSO 6.8 — il corpo di POST /shows/{id}/reserve e /release.
 *
 * E' la copia locale di SeatsRequest di shows-service. Stesso JSON, due
 * record in due repository: e' la duplicazione voluta del passo 6.8.
 *
 * I nomi dei campi devono combaciare con quelli dell'altro contratto — e'
 * l'unica cosa che li tiene insieme, e nessun compilatore lo verifica.
 * Il giorno in cui non combaciano piu' lo dice un 400, a runtime: e' il
 * prezzo della copia, e al G10 si paga con un test a contratto.
 */
public record SeatsJson(String sagaId, int quantity) {
}
