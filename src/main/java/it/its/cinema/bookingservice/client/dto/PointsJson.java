package it.its.cinema.bookingservice.client.dto;

/**
 * PASSO 8.2 — il corpo di /credit e /debit.
 *
 * Il flag "compensazione" viaggia solo verso /debit, e vale true SOLO quando
 * la saga sta tornando indietro: e' cio' che dice a loyalty-service che un
 * saldo insufficiente non e' un no da restituire, ma una compensazione da
 * completare per quanto si puo'.
 */
public record PointsJson(String sagaId, int points, boolean compensazione) {
}
