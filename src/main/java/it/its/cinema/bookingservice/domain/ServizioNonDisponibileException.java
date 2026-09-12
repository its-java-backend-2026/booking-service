package it.its.cinema.bookingservice.domain;

import lombok.Getter;

/**
 * PASSO 6.9 — IL GUASTO DI QUALCUN ALTRO, CHE NON E' UN NOSTRO BUG.
 *
 * Ci arriva in tre modi, e sono la stessa cosa vista da tre lati:
 *
 *   - il servizio a valle ha risposto 5xx      (c'e', ma si e' rotto)
 *   - la connessione e' stata rifiutata        (non c'e')
 *   - e' scaduto il timeout del passo 6.6      (c'e', ma non risponde)
 *
 * Diventa un 503 e NON un 500, ed e' la distinzione che vale il passo:
 *
 *   500 = "colpa nostra, un bug". Manda in caccia la persona sbagliata e
 *         dice al client che riprovare e' inutile.
 *   503 = "il nostro codice ha funzionato, un servizio a valle no". Dice
 *         al client che riprovare fra poco ha senso, e a chi sorveglia il
 *         sistema dove guardare.
 *
 * Dal G7 e' questa l'eccezione che fa scattare il retry e conta per il
 * circuit breaker (passo 7.3, retryExceptions). Per questo e' una classe
 * sola per tutti i servizi a valle e porta con se' il nome di quale:
 * il nome serve al messaggio e ai log, il TIPO serve a Resilience4j.
 */
@Getter
public class ServizioNonDisponibileException extends RuntimeException {

    /** Quale servizio non ha risposto: "shows-service", "pricing-service". */
    private final String servizio;

    public ServizioNonDisponibileException(String servizio, String motivo, Throwable causa) {
        super(servizio + " non ha risposto: " + motivo, causa);
        this.servizio = servizio;
    }

    public ServizioNonDisponibileException(String servizio, String motivo) {
        this(servizio, motivo, null);
    }
}
