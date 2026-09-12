package it.its.cinema.bookingservice.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/**
 * PASSO 6.8 — la risposta di POST /prices/quote, copia locale.
 *
 * Un campo solo, ed e' quello che ci serve. ignoreUnknown lascia a pricing la
 * liberta' di aggiungerne (lo sconto applicato, il dettaglio per la ricevuta)
 * senza rompere noi.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record QuoteResponseJson(BigDecimal unitPrice) {
}
