package it.its.cinema.bookingservice.domain;

/**
 * PASSO 8.4 — I TRE STATI DI UNA PRENOTAZIONE.
 *
 * Fino al G7 non servivano: una prenotazione o veniva salvata, o non
 * esisteva. Dal G8 c'e' un momento — che dura il tempo di tre chiamate di
 * rete — in cui la riga esiste ma l'acquisto non e' ancora concluso, e
 * quel momento va chiamato per nome.
 */
public enum StatoPrenotazione {

    /**
     * La saga e' in corso: la riga c'e', l'acquisto no. Nessun biglietto da
     * stampare, e un lavoro periodico che trovasse questa riga ferma da
     * mezz'ora saprebbe che c'e' qualcosa da riprendere.
     */
    IN_CORSO,

    /** Tutti i passi sono andati a buon fine. E' il biglietto. */
    CONFERMATA,

    /**
     * La saga e' fallita e ha compensato. La riga RESTA, e non e' pigrizia:
     * cancellarla toglierebbe l'unica risposta alla domanda "ho provato a
     * comprare e non ha funzionato, cosa e' successo?".
     */
    FALLITA
}
