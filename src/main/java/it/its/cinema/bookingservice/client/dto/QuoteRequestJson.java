package it.its.cinema.bookingservice.client.dto;

import java.math.BigDecimal;

/**
 * PASSO 6.8 — il corpo di POST /prices/quote, copia locale.
 *
 * customerType e' una String e non il nostro enum CustomerType, e la scelta
 * e' deliberata: qui siamo sul CONFINE, e sul confine viaggia il nome, non
 * il tipo. Se domani accettassimo una categoria che pricing non conosce, il
 * nostro codice compilerebbe lo stesso e riceveremmo un 400 chiaro da chi di
 * dovere — invece di non compilare per colpa di un enum di qualcun altro.
 */
public record QuoteRequestJson(BigDecimal basePrice, String customerType, boolean eveningShow) {
}
