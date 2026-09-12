package it.its.cinema.bookingservice.domain;

/**
 * Eccezione di dominio, non di web: non conosce HttpStatus.
 * E' il livello web a decidere che questa diventa un 404.
 */
public class BookingNotFoundException extends RuntimeException {

    public BookingNotFoundException(Long id) {
        super("Prenotazione non trovata: " + id);
    }
}
