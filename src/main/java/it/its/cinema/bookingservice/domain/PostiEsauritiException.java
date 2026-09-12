package it.its.cinema.bookingservice.domain;

/**
 * PASSO 6.9 — IL 409 DELL'ALTRO SERVIZIO.
 *
 * shows-service ha risposto 409: i posti non bastano. Resta un 409 anche per
 * il nostro chiamante, e per la stessa ragione per cui lo era la': la
 * richiesta e' scritta benissimo, e' lo STATO dello spettacolo a renderla
 * impossibile. La stessa identica richiesta, un'ora prima, sarebbe riuscita.
 *
 * Attenzione a non tradurlo in 400: direbbe al client "hai sbagliato a
 * scrivere" e lo manderebbe a correggere un JSON che era giusto.
 */
public class PostiEsauritiException extends RuntimeException {

    public PostiEsauritiException(Long showId, int richiesti) {
        super("Posti insufficienti per lo spettacolo " + showId
                + ": ne sono stati richiesti " + richiesti);
    }
}
