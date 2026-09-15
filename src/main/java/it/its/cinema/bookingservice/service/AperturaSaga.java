package it.its.cinema.bookingservice.service;

import it.its.cinema.bookingservice.domain.Booking;
import it.its.cinema.bookingservice.domain.SagaState;

/**
 * PASSO 8.4 — le due righe che nascono insieme.
 *
 * La prenotazione (IN_CORSO) e lo stato della saga (AVVIATA) si scrivono
 * nella stessa transazione, e da quel momento viaggiano in coppia. Un record
 * e non due valori di ritorno separati: cosi' non ci si puo' dimenticare di
 * uno dei due, ed e' impossibile avere in mano una saga senza la sua
 * prenotazione.
 */
public record AperturaSaga(Booking prenotazione, SagaState saga) {
}
