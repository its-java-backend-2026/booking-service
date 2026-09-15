package it.its.cinema.bookingservice.domain;

/**
 * PASSO 8.4 — COM'E' FINITA LA SAGA.
 *
 * I primi tre sono ovvi. Il quarto e' quello che distingue un sistema
 * distribuito raccontato da uno costruito.
 */
public enum StatoSaga {

    /**
     * In corso. Una riga che resta qui per piu' di qualche secondo e' una
     * saga interrotta: e' esattamente cio' che la tabella esiste per
     * rendere trovabile.
     */
    IN_CORSO,

    /** Tutti i passi riusciti. */
    COMPLETATA,

    /** Un passo e' fallito, e tutte le compensazioni sono riuscite. */
    COMPENSATA,

    /**
     * ===================================================================
     * UN PASSO E' FALLITO, E LA COMPENSAZIONE NON HA RIMESSO TUTTO A POSTO.
     *
     * Succede in due modi, ed e' bene averli tutti e due in mente:
     *
     *   la compensazione FALLISCE   loyalty-service non risponde mentre
     *                               proviamo a stornare i punti. Il resto
     *                               si compensa lo stesso (passo 8.7), ma
     *                               quei punti restano dati.
     *
     *   la compensazione RIESCE     i punti erano stati accreditati e il
     *   SOLO IN PARTE               cliente li ha gia' spesi: il saldo non
     *                               va sotto zero (passo 8.2), quindi si
     *                               toglie cio' che c'e'.
     *
     * In tutti e due i casi il sistema NON e' tornato com'era, e questo
     * stato serve a dirlo invece di far finta di niente. E' la differenza
     * fra una saga e una transazione: una transazione o c'e' stata o no,
     * una saga puo' finire in un punto intermedio — e allora deve saperlo
     * dire, perche' a sistemarlo sara' una persona.
     * ===================================================================
     */
    COMPENSAZIONE_PARZIALE
}
