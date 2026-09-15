package it.its.cinema.bookingservice.client;

import java.math.BigDecimal;
import java.util.function.Supplier;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import it.its.cinema.bookingservice.client.dto.AuthorizeJson;
import it.its.cinema.bookingservice.client.dto.PaymentJson;
import it.its.cinema.bookingservice.domain.PagamentoRifiutatoException;
import it.its.cinema.bookingservice.domain.ServizioNonDisponibileException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * PASSO 8.1 — IL GATEWAY VERSO payment-service.
 *
 * ===========================================================================
 * LA TRADUZIONE, E IL CODICE NUOVO DELLA GIORNATA.
 *
 *   402 di payment     ->  PagamentoRifiutatoException   ->  402 nostro
 *   404 su /refund     ->  ignorato (non c'era niente da stornare)
 *   5xx / timeout      ->  ServizioNonDisponibile        ->  503 nostro
 *   altri 4xx          ->  NON tradotti                  ->  500 nostro
 *
 * La prima riga e' quella da capire bene, perche' e' la ragione per cui
 * esiste tutta la giornata. Un 402 NON e' un guasto: e' la risposta che fa
 * partire la compensazione (passo 8.7). Tradurlo in 503 sarebbe l'errore
 * piu' costoso possibile — chi ci chiama ritenterebbe un rifiuto, il circuit
 * breaker si aprirebbe dopo dieci carte scadute, e nessuno compenserebbe
 * niente.
 * ===========================================================================
 *
 * ===========================================================================
 * PASSO 7.3 — E QUI NON C'E' @Retry, SU NESSUNO DEI DUE METODI.
 *
 * Su /authorize la regola e' scritta nel passo 7.3 e non ha eccezioni:
 * nessun retry su payment. Vale la pena essere precisi sul perche', perche'
 * la ragione superficiale ("il rifiuto e' una risposta di dominio") oggi
 * non basterebbe piu' — il rifiuto e' un'eccezione che non sta fra le
 * retryExceptions, quindi non verrebbe ritentato comunque.
 *
 * Il motivo vero e' l'altro: il TIMEOUT. Payment e' idempotente sul sagaId
 * (vincolo UNIQUE, passo 8.1), quindi tecnicamente un retry sarebbe sicuro.
 * Ma con i soldi il margine si tiene largo: un'autorizzazione di cui non si
 * conosce l'esito e' una cosa che si riconcilia guardando, non ritentando al
 * buio. La saga fallisce, compensa, e il cliente riprova — con una chiave di
 * idempotenza nuova e un esito che qualcuno ha visto.
 *
 * Su /refund il motivo e' diverso e piu' semplice: la compensazione non deve
 * MAI bloccare le altre (passo 8.7). Un retry qui aggiungerebbe fino a due
 * secondi di attesa prima di poter rilasciare i posti, che e' la
 * compensazione che ai clienti in fila interessa davvero.
 *
 * Il BREAKER invece c'e' su tutti e due: rifiutarsi di chiamare non ha mai
 * effetti collaterali.
 * ===========================================================================
 */
@Component
@Slf4j
public class PaymentClient {

    private static final String SERVIZIO = "payment-service";

    private final RestClient http;

    public PaymentClient(@Qualifier("paymentRestClient") RestClient http) {
        this.http = http;
    }

    /**
     * PASSO 2 DELLA SAGA (dopo la riserva dei posti) — l'autorizzazione.
     *
     * O ritorna, o solleva: non esiste un "forse". Un'eccezione qui fa
     * partire la compensazione, e le due eccezioni possibili portano la
     * saga su due strade completamente diverse — vedi il commento in cima.
     */
    @CircuitBreaker(name = "payment", fallbackMethod = "autorizzazioneNonDisponibile")
    public void autorizza(String sagaId, Long bookingId, BigDecimal importo) {
        String operazione = "POST /payments/authorize [saga " + sagaId + "]";
        try {
            http.post()
                    .uri("/payments/authorize")
                    .body(new AuthorizeJson(sagaId, bookingId, importo))
                    .retrieve()
                    .onStatus(HttpStatusCode::is5xxServerError,
                            (richiesta, risposta) -> {
                                throw new ServizioNonDisponibileException(SERVIZIO,
                                        "ha risposto " + risposta.getStatusCode());
                            })
                    .body(PaymentJson.class);

        } catch (HttpClientErrorException e) {
            // =============================================================
            // IL 402, E PERCHE' SI PRENDE DI QUI E NON DA UN onStatus.
            //
            // Dentro un onStatus si ha in mano la risposta GREZZA: per
            // leggerne il corpo bisognerebbe deserializzarlo a mano,
            // scegliendo fra i due Jackson che stanno sul classpath di Boot
            // 4. HttpClientErrorException invece ha gia' il corpo letto, e
            // getResponseBodyAs lo converte con lo stesso convertitore che
            // usa tutto il resto dell'applicazione.
            //
            // Serve perche' il MOTIVO conta: "importo 200.00 oltre la soglia
            // di 100.00" e' un'informazione che il cliente puo' usare,
            // "pagamento rifiutato" non lo e'.
            // =============================================================
            if (e.getStatusCode().value() == HttpStatus.PAYMENT_REQUIRED.value()) {
                throw new PagamentoRifiutatoException(sagaId, motivoDi(e));
            }
            // Ogni altro 4xx e' un disallineamento di contratto, cioe' un bug
            // NOSTRO (passo 6.9): risale intatto e diventa un 500, con lo
            // stack trace nei log dove serve.
            throw e;

        } catch (ResourceAccessException e) {
            // Connessione rifiutata, host irraggiungibile o timeout: non
            // c'e' nessuna risposta, quindi nessun onStatus e' scattato.
            log.warn("{} non raggiungibile su {}: {}", SERVIZIO, operazione, e.getMessage());
            throw new ServizioNonDisponibileException(SERVIZIO, e.getMessage(), e);
        }
    }

    /**
     * PASSO 8.7 — LA COMPENSAZIONE: storna l'autorizzazione.
     *
     * Ignora il 404, e la ragione e' la stessa di tutte le compensazioni: se
     * di questa saga non risulta nessun pagamento, non c'e' niente da
     * rimettere a posto — ed e' un successo, non un errore. Succede davvero:
     * la saga fallisce PRIMA di aver pagato e la compensazione passa di qui
     * solo se il passo era stato raggiunto (passo 8.7), ma una rete che ha
     * perso la risposta di /authorize puo' aver lasciato le due parti con
     * idee diverse su cosa sia successo.
     */
    @CircuitBreaker(name = "payment", fallbackMethod = "stornoNonDisponibile")
    public void storna(String sagaId) {
        eseguendo("POST /payments/" + sagaId + "/refund", () -> http.post()
                .uri("/payments/{sagaId}/refund", sagaId)
                .retrieve()
                .onStatus(stato -> stato.value() == HttpStatus.NOT_FOUND.value(),
                        (richiesta, risposta) -> {
                            log.info("[{}] nessun pagamento da stornare per la saga {}: "
                                    + "niente da rimettere a posto", SERVIZIO, sagaId);
                        })
                .onStatus(HttpStatusCode::is5xxServerError,
                        (richiesta, risposta) -> {
                            throw new ServizioNonDisponibileException(SERVIZIO,
                                    "ha risposto " + risposta.getStatusCode());
                        })
                .toBodilessEntity());
    }

    // ---------------------------------------------------------------------
    // PASSO 7.5 — I FALLBACK, CHE NON INVENTANO NIENTE.
    //
    // E qui la tentazione di inventare sarebbe fortissima: "payment non
    // risponde, facciamo passare l'acquisto e sistemiamo dopo". Sarebbe
    // regalare biglietti, in silenzio, esattamente mentre il sistema e' in
    // difficolta' e nessuno sta guardando.
    //
    // Il fallback serve alle due cose di sempre: loggare la causa VERA prima
    // che si perda, e tradurre il circuito aperto in un 503 invece che in un
    // 500. Tutto il resto risale intatto — e "tutto il resto" qui comprende
    // il rifiuto, che DEVE arrivare fino alla saga per farla compensare.
    // ---------------------------------------------------------------------

    private void autorizzazioneNonDisponibile(String sagaId, Long bookingId,
                                              BigDecimal importo, Throwable causa) {
        throw tradotto("POST /payments/authorize [saga " + sagaId + "]", causa);
    }

    private void stornoNonDisponibile(String sagaId, Throwable causa) {
        throw tradotto("POST /payments/" + sagaId + "/refund", causa);
    }

    private RuntimeException tradotto(String operazione, Throwable causa) {
        log.warn("[{}] fallback su {}: {}", SERVIZIO, operazione, causa.toString());

        if (causa instanceof CallNotPermittedException) {
            return new ServizioNonDisponibileException(SERVIZIO,
                    "circuito aperto, la chiamata non e' stata nemmeno tentata", causa);
        }
        // Il rifiuto passa di qui INTATTO: e' un'eccezione di dominio, e la
        // saga la sta aspettando per compensare.
        if (causa instanceof RuntimeException gia) {
            return gia;
        }
        return new ServizioNonDisponibileException(SERVIZIO, causa.toString(), causa);
    }

    /**
     * Il "detail" del ProblemDetail del rifiuto, quando c'e'.
     *
     * Un fallback generoso: qui si sta gia' gestendo un rifiuto, e fallire
     * mentre si cerca di spiegarlo meglio sarebbe il modo peggiore di
     * peggiorare le cose. Se il corpo non e' un ProblemDetail — perche' un
     * proxy ha risposto al posto suo, o perche' il contratto e' cambiato —
     * il rifiuto resta un rifiuto, solo senza spiegazione.
     */
    private String motivoDi(HttpClientErrorException e) {
        try {
            ProblemDetail problema = e.getResponseBodyAs(ProblemDetail.class);
            if (problema != null && problema.getDetail() != null) {
                return problema.getDetail();
            }
        } catch (RuntimeException lettura) {
            log.debug("[{}] non sono riuscito a leggere il motivo del rifiuto: {}",
                    SERVIZIO, lettura.toString());
        }
        return "nessun motivo indicato";
    }

    private <T> T eseguendo(String operazione, Supplier<T> chiamata) {
        try {
            return chiamata.get();
        } catch (ResourceAccessException e) {
            log.warn("{} non raggiungibile su {}: {}", SERVIZIO, operazione, e.getMessage());
            throw new ServizioNonDisponibileException(SERVIZIO, e.getMessage(), e);
        }
    }
}
