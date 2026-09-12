package it.its.cinema.bookingservice.domain;

/**
 * PASSO 6.9 — IL 404 DELL'ALTRO SERVIZIO, TRADOTTO NEL NOSTRO LINGUAGGIO.
 *
 * shows-service ha risposto 404. Non ripetiamo "404" per tutto il codice:
 * il client HTTP traduce subito in questa eccezione di dominio, e solo
 * GestoreErrori decide che diventa un 404 anche per il NOSTRO chiamante.
 *
 * Perche' e' giusto che resti un 404: l'utente ha chiesto di prenotare uno
 * spettacolo che non esiste. E' un errore suo, non un guasto nostro, e la
 * risposta e' la stessa che avrebbe ricevuto chiedendolo direttamente.
 */
public class SpettacoloNonTrovatoException extends RuntimeException {

    public SpettacoloNonTrovatoException(Long showId) {
        super("Spettacolo non trovato: " + showId);
    }
}
