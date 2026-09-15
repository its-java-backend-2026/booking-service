package it.its.cinema.bookingservice.web;

import it.its.cinema.bookingservice.domain.BookingNotFoundException;
import it.its.cinema.bookingservice.domain.PostiEsauritiException;
import it.its.cinema.bookingservice.domain.ServizioNonDisponibileException;
import it.its.cinema.bookingservice.domain.SpettacoloNonTrovatoException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PASSO 6.9 — LA TRADUZIONE, ULTIMO TRATTO.
 *
 * ShowsClient e PricingClient hanno gia' trasformato i codici HTTP altrui in
 * eccezioni NOSTRE. Qui quelle eccezioni tornano a essere codici HTTP, ma
 * verso il NOSTRO chiamante. Sembra un giro inutile e non lo e': in mezzo, il
 * service e il controller hanno potuto lavorare senza sapere niente di HTTP.
 *
 *     404 di shows   ->  SpettacoloNonTrovatoException     ->  404 nostro
 *     409 di shows   ->  PostiEsauritiException            ->  409 nostro
 *     5xx / timeout  ->  ServizioNonDisponibileException   ->  503 nostro
 *     altri 4xx      ->  (non tradotti)                    ->  500 nostro
 *
 * L'ultima riga e' quella su cui tornare: un 400 da un servizio a valle
 * significa che SIAMO NOI ad avergli mandato una richiesta sbagliata. E' un
 * bug nostro, e un bug nostro e' un 500 con lo stack trace nei log.
 * Raccontarlo come 503 lo nasconderebbe dietro "e' giu' qualcun altro".
 */
@RestControllerAdvice
@Slf4j
public class GestoreErrori extends ResponseEntityExceptionHandler {

    private static final String BASE_TYPE = "https://cinema.its.it/errori/";

    // ---------------------------------------------------------------- 404

    @ExceptionHandler(BookingNotFoundException.class)
    public ProblemDetail prenotazioneNonTrovata(BookingNotFoundException e) {
        return problema(HttpStatus.NOT_FOUND, "Prenotazione non trovata",
                "prenotazione-non-trovata", e.getMessage());
    }

    /**
     * Il 404 di shows-service resta un 404.
     *
     * L'utente ha chiesto di prenotare uno spettacolo che non esiste: e' un
     * errore suo, e la risposta e' la stessa che avrebbe ricevuto chiedendo
     * lo spettacolo direttamente. Tradurlo in 500 direbbe "mi sono rotto"
     * per una richiesta che non era nostra colpa.
     */
    @ExceptionHandler(SpettacoloNonTrovatoException.class)
    public ProblemDetail spettacoloNonTrovato(SpettacoloNonTrovatoException e) {
        return problema(HttpStatus.NOT_FOUND, "Spettacolo non trovato",
                "spettacolo-non-trovato", e.getMessage());
    }

    // ---------------------------------------------------------------- 409

    /**
     * Il 409 di shows-service resta un 409, e non diventa un 400.
     *
     * La richiesta era scritta benissimo: e' lo STATO dello spettacolo a
     * renderla impossibile, e la stessa identica richiesta mandata un'ora
     * prima sarebbe riuscita. Un 400 manderebbe il client a correggere un
     * JSON che era giusto.
     */
    @ExceptionHandler(PostiEsauritiException.class)
    public ProblemDetail postiEsauriti(PostiEsauritiException e) {
        return problema(HttpStatus.CONFLICT, "Posti insufficienti",
                "posti-insufficienti", e.getMessage());
    }

    /**
     * PASSO 6.4 — il vincolo UNIQUE su saga_id.
     *
     * Non ci si arriva quasi mai, e dal G7 ancora meno: il sagaId lo
     * generiamo con UUID.randomUUID() a ogni tentativo, e la violazione del
     * vincolo su idempotency_key la tratta gia' BookingService, che la
     * traduce nella prenotazione vincente invece che in un errore (passo
     * 7.6). Qui resta la rete per il caso che non sappiamo spiegare.
     *
     * 409 e non 500: l'operazione non e' fallita per un nostro difetto, e'
     * stata rifiutata perche' risultava gia' registrata.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail violazioneDiVincolo(DataIntegrityViolationException e) {
        log.warn("violazione di un vincolo del database", e);
        return problema(HttpStatus.CONFLICT, "Operazione gia' registrata",
                "operazione-gia-registrata",
                "Questa operazione risulta gia' registrata.");
    }

    // ---------------------------------------------------------------- 503

    /**
     * IL CUORE DEL PASSO 6.9: 503, MAI 500.
     *
     * Il nostro codice ha funzionato; e' un servizio a valle che non c'era,
     * si e' rotto o non ha risposto in tempo.
     *
     *   500 direbbe "colpa nostra, un bug": manda in caccia la persona
     *       sbagliata e dice al client che riprovare e' inutile.
     *   503 dice la verita': riprovare fra poco ha senso, e chi sorveglia il
     *       sistema sa dove guardare.
     *
     * Retry-After non e' decorazione: dice QUANDO riprovare, e un client
     * educato — o il Resilience4j del G7 — lo rispetta invece di martellare
     * un servizio che e' gia' in difficolta'.
     *
     * Il log e' WARN e non ERROR, ed e' voluto: non e' un nostro difetto da
     * correggere. Diventa un problema nostro se succede spesso, e per quello
     * servono le metriche del G9, non una riga di ERROR per ogni occorrenza.
     */
    @ExceptionHandler(ServizioNonDisponibileException.class)
    public ResponseEntity<ProblemDetail> servizioNonDisponibile(
            ServizioNonDisponibileException e) {

        log.warn("servizio a valle non disponibile: {}", e.getMessage());

        ProblemDetail corpo = problema(HttpStatus.SERVICE_UNAVAILABLE,
                "Servizio non disponibile",
                "servizio-non-disponibile",
                e.getMessage());
        // Quale servizio manca e' un'informazione utile a chi opera, e non
        // rivela niente di sensibile: sono nomi interni, non indirizzi.
        corpo.setProperty("servizio", e.getServizio());

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "10")
                .body(corpo);
    }

    // ---------------------------------------------------------------- 400

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail richiestaNonValida(IllegalArgumentException e) {
        return problema(HttpStatus.BAD_REQUEST, "Richiesta non valida",
                "richiesta-non-valida", e.getMessage());
    }

    /**
     * PASSO 7.6 — l'header Idempotency-Key non c'e'.
     *
     * Senza questo metodo la risposta sarebbe comunque un 400: ci pensa la
     * classe base, che gestisce MissingRequestHeaderException. Il messaggio
     * pero' sarebbe quello di Spring ("Required header ... is not present"),
     * che dice cosa manca e non dice cosa farne.
     *
     * Vale la riga in piu' perche' questo e' l'errore che incontra CHIUNQUE
     * provi la POST la prima volta dopo il G7, e la risposta puo' dirgli
     * direttamente come uscirne.
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ProblemDetail headerMancante(MissingRequestHeaderException e) {
        if ("Idempotency-Key".equalsIgnoreCase(e.getHeaderName())) {
            return problema(HttpStatus.BAD_REQUEST, "Idempotency-Key mancante",
                    "idempotency-key-mancante",
                    "La prenotazione richiede l'header Idempotency-Key: una stringa "
                            + "scelta da chi chiama, la stessa a ogni ripetizione della "
                            + "stessa richiesta (un UUID va benissimo).");
        }
        return problema(HttpStatus.BAD_REQUEST, "Header mancante", "header-mancante",
                "Manca l'header obbligatorio '" + e.getHeaderName() + "'.");
    }

    /** GET /bookings/abc — l'id nel path non e' un numero. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail tipoSbagliato(MethodArgumentTypeMismatchException e) {
        return problema(HttpStatus.BAD_REQUEST, "Parametro non valido",
                "parametro-non-valido",
                "Il parametro '" + e.getName() + "' non accetta il valore '"
                        + e.getValue() + "'.");
    }

    /** Il fallimento di @Valid, con l'elenco dei campi rifiutati. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        Map<String, String> campi = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(errore ->
                campi.putIfAbsent(errore.getField(), errore.getDefaultMessage()));

        ProblemDetail corpo = problema(HttpStatus.BAD_REQUEST, "Dati non validi",
                "dati-non-validi",
                "La richiesta contiene " + campi.size() + " campo/i non valido/i.");
        corpo.setProperty("errors", campi);

        return handleExceptionInternal(ex, corpo, headers, status, request);
    }

    // ---------------------------------------------------------------- 500

    /**
     * L'ultima rete, e qui ci finisce un caso preciso da riconoscere: il 4xx
     * NON tradotto di un servizio a valle (un 400 perche' il nostro JSON non
     * gli piace, un 415 per un Content-Type sbagliato). Sono disallineamenti
     * di contratto, cioe' bug nostri, e vanno letti nei log con lo stack
     * trace — non nascosti dietro un 503.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail erroreInterno(Exception e) {
        log.error("Errore non gestito", e);
        return problema(HttpStatus.INTERNAL_SERVER_ERROR, "Errore interno",
                "errore-interno",
                "Si e' verificato un errore imprevisto. Se il problema persiste, "
                        + "segnalalo indicando l'ora esatta del tentativo.");
    }

    // ---------------------------------------------------------------- utilita'

    private ProblemDetail problema(HttpStatus stato, String titolo, String tipo, String dettaglio) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(stato, dettaglio);
        p.setTitle(titolo);
        p.setType(URI.create(BASE_TYPE + tipo));
        return p;
    }
}
