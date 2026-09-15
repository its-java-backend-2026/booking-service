package it.its.cinema.bookingservice.domain;

/**
 * PASSO 8.4 — IL PASSO RAGGIUNTO, IN ORDINE.
 *
 * L'ordine di dichiarazione non e' estetico: ordinal() lo usa haRaggiunto()
 * per rispondere alla domanda "sono arrivato almeno fin qui?", che e' cio'
 * su cui si decide la compensazione del passo 8.7.
 *
 * ATTENZIONE, ED E' LA TRAPPOLA DI QUESTO FILE: l'ordinale conta QUI DENTRO
 * e non deve MAI finire nel database. Sul database si scrive il nome
 * (EnumType.STRING), altrimenti inserire un passo in mezzo all'elenco
 * cambierebbe il significato di tutte le righe gia' scritte — e delle
 * compensazioni che un giorno partiranno da quelle righe.
 */
public enum PassoSaga {

    /** La prenotazione e' stata registrata, e non e' successo altro. */
    AVVIATA,

    /** shows-service ha scalato i posti. Da qui in poi c'e' da compensare. */
    POSTI_RISERVATI,

    /** payment-service ha autorizzato l'importo. */
    PAGATO,

    /** loyalty-service ha accreditato i punti. E' l'ultimo passo. */
    PUNTI_ACCREDITATI;

    /**
     * "Sono arrivato almeno fino a questo passo?"
     *
     * E' l'unica domanda che la compensazione deve porsi (passo 8.7): si
     * guarda lo STATO RAGGIUNTO, non l'ordine in cui sono state fatte le
     * chiamate, e non quale eccezione e' arrivata. Un'eccezione dice cosa e'
     * andato storto; solo questo dice cosa c'era da rimettere a posto.
     */
    public boolean almeno(PassoSaga passo) {
        return this.ordinal() >= passo.ordinal();
    }
}
