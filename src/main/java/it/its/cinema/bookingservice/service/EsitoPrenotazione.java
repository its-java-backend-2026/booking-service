package it.its.cinema.bookingservice.service;

import it.its.cinema.bookingservice.domain.Booking;

/**
 * PASSO 7.6 — CIO' CHE IL SERVICE HA FATTO, OLTRE A CIO' CHE RESTITUISCE.
 *
 * Con l'idempotenza, "prenota" ha due esiti che portano allo STESSO oggetto
 * per due strade diverse:
 *
 *   creata adesso   tre chiamate HTTP, i posti scalati, una riga nuova
 *   gia' esistente  nessuna chiamata a nessuno, la riga di prima
 *
 * Restituire solo il Booking li renderebbe indistinguibili, e il controller
 * non potrebbe rispondere 201 nel primo caso e 200 nel secondo. La domanda
 * "l'ho creata io adesso?" e' un'informazione del service, non dello strato
 * web: il controller non ha modo di ricavarla da solo.
 *
 * Un record e non un boolean di ritorno accanto: cosi' la coppia viaggia
 * insieme e non ci si puo' dimenticare di guardarla.
 */
public record EsitoPrenotazione(Booking prenotazione, boolean giaEsistente) {

    static EsitoPrenotazione creata(Booking prenotazione) {
        return new EsitoPrenotazione(prenotazione, false);
    }

    static EsitoPrenotazione ripetuta(Booking prenotazione) {
        return new EsitoPrenotazione(prenotazione, true);
    }
}
