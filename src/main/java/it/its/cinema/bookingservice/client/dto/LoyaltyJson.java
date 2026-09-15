package it.its.cinema.bookingservice.client.dto;

/**
 * PASSO 8.2 — la risposta di loyalty-service, e i due campi che contano.
 *
 * "applicati" puo' essere minore di "richiesti": e' la compensazione
 * imperfetta. Senza questi due numeri, una saga che ha potuto stornare solo
 * un quarto dei punti risulterebbe compensata benissimo — e il fatto che il
 * cinema abbia regalato il resto non uscirebbe mai da loyalty-service.
 */
public record LoyaltyJson(String customerId, int points, int richiesti, int applicati,
                          boolean ripetuta, boolean parziale) {
}
