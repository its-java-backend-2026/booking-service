package it.its.cinema.bookingservice.client;

import java.util.function.Supplier;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import it.its.cinema.bookingservice.client.dto.LoyaltyJson;
import it.its.cinema.bookingservice.client.dto.PointsJson;
import it.its.cinema.bookingservice.domain.ServizioNonDisponibileException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * PASSO 8.2 — IL GATEWAY VERSO loyalty-service.
 *
 * ===========================================================================
 * E QUI IL @Retry C'E' SU TUTTI E DUE I METODI, COMPRESA LA SCRITTURA.
 *
 * Fino al G7 la regola era: retry solo sulle letture e sui calcoli puri,
 * perche' ripetere una scrittura la esegue due volte. Qui si ritenta una
 * POST che muove un contatore, e non e' un'incoerenza: e' il passo 8.3 che
 * cambia la premessa.
 *
 * loyalty-service registra ogni operazione su (saga_id, operation_type) con
 * un vincolo UNIQUE, quindi la seconda chiamata identica NON FA NIENTE e
 * risponde come la prima. Ripeterla e' gratis.
 *
 * E' la stessa cosa che accade a POST /shows/{id}/reserve, che dal G8 ha
 * finalmente il suo @Retry dopo due giornate di commenti che spiegavano
 * perche' non poteva averlo.
 *
 * LA REGOLA NON E' CAMBIATA, E VALE LA PENA DIRLO IN CHIARO: si ritenta cio'
 * che e' IDEMPOTENTE. Quello che e' cambiato e' che adesso lo sono.
 * ===========================================================================
 */
@Component
@Slf4j
public class LoyaltyClient {

    private static final String SERVIZIO = "loyalty-service";

    private final RestClient http;

    public LoyaltyClient(@Qualifier("loyaltyRestClient") RestClient http) {
        this.http = http;
    }

    /**
     * PASSO 3 DELLA SAGA — i punti guadagnati con l'acquisto.
     *
     * E' l'ULTIMO passo, e la posizione conta: quando fallisce lui, i posti
     * sono gia' scalati e il pagamento e' gia' passato. E' il caso in cui la
     * compensazione ha piu' lavoro da fare, ed e' per questo che merita di
     * stare in fondo in un corso — un passo che non ha niente dietro non
     * insegna niente sulle compensazioni.
     */
    @CircuitBreaker(name = "loyalty", fallbackMethod = "accreditoNonDisponibile")
    @Retry(name = "loyalty")
    public void accredita(String sagaId, String customerId, int punti) {
        eseguendo("POST /loyalty/" + customerId + "/credit [saga " + sagaId + "]",
                () -> http.post()
                        .uri("/loyalty/{customerId}/credit", customerId)
                        .body(new PointsJson(sagaId, punti, false))
                        .retrieve()
                        .onStatus(HttpStatusCode::is5xxServerError,
                                (richiesta, risposta) -> {
                                    throw new ServizioNonDisponibileException(SERVIZIO,
                                            "ha risposto " + risposta.getStatusCode());
                                })
                        .body(LoyaltyJson.class));
    }

    /**
     * PASSO 8.7 — LA COMPENSAZIONE, E RESTITUISCE SE E' RIUSCITA PER INTERO.
     *
     * ===================================================================
     * IL VALORE DI RITORNO E' LA PARTE INTERESSANTE DI TUTTO IL FILE.
     *
     * Le altre compensazioni rispondono si' o no: i posti tornano, il
     * pagamento si storna. Questa puo' rispondere "in parte" — se il cliente
     * ha gia' speso i punti, il saldo non va sotto zero (passo 8.2) e si
     * toglie cio' che c'e'.
     *
     * Con un metodo void quella differenza morirebbe qui dentro, e la saga
     * si chiuderebbe come COMPENSATA raccontando che tutto e' tornato com'era.
     * Restituendo il flag, la saga sa di essere COMPENSAZIONE_PARZIALE —
     * cioe' che c'e' qualcosa che una persona dovra' guardare.
     *
     * "compensazione: true" nel corpo e' l'altra meta': dice a
     * loyalty-service che un saldo insufficiente, QUI, non e' un no da
     * restituire. Senza quel flag la compensazione riceverebbe un 409 e si
     * bloccherebbe — e la saga resterebbe con i punti dati e i posti
     * bloccati.
     * ===================================================================
     *
     * @return true se sono stati stornati TUTTI i punti richiesti
     */
    @CircuitBreaker(name = "loyalty", fallbackMethod = "stornoNonDisponibile")
    @Retry(name = "loyalty")
    public boolean storna(String sagaId, String customerId, int punti) {
        LoyaltyJson esito = eseguendo(
                "POST /loyalty/" + customerId + "/debit [saga " + sagaId + "]",
                () -> http.post()
                        .uri("/loyalty/{customerId}/debit", customerId)
                        .body(new PointsJson(sagaId, punti, true))
                        .retrieve()
                        .onStatus(HttpStatusCode::is5xxServerError,
                                (richiesta, risposta) -> {
                                    throw new ServizioNonDisponibileException(SERVIZIO,
                                            "ha risposto " + risposta.getStatusCode());
                                })
                        .body(LoyaltyJson.class));

        // Un 200 con corpo vuoto: non e' teoria, capita quando l'altro
        // servizio cambia il nome di un campo o un proxy si mette in mezzo.
        // Nel dubbio si dichiara la compensazione INCOMPLETA: fra i due
        // errori possibili, dire "guarda questa saga" e' meno grave di dire
        // "e' tutto a posto" senza saperlo.
        if (esito == null) {
            log.warn("[{}] storno punti senza risposta leggibile per la saga {}: "
                    + "dichiaro la compensazione incompleta", SERVIZIO, sagaId);
            return false;
        }
        if (esito.parziale()) {
            log.warn("[{}] compensazione PARZIALE per la saga {}: chiesti {} punti, "
                    + "tolti {}", SERVIZIO, sagaId, esito.richiesti(), esito.applicati());
        }
        return !esito.parziale();
    }

    // ---------------------------------------------------------------------
    // PASSO 7.5 — i fallback: la causa vera nel log, il circuito aperto
    // tradotto in 503. Nessun punto inventato.
    // ---------------------------------------------------------------------

    private void accreditoNonDisponibile(String sagaId, String customerId,
                                         int punti, Throwable causa) {
        throw tradotto("POST /loyalty/" + customerId + "/credit [saga " + sagaId + "]", causa);
    }

    private boolean stornoNonDisponibile(String sagaId, String customerId,
                                         int punti, Throwable causa) {
        throw tradotto("POST /loyalty/" + customerId + "/debit [saga " + sagaId + "]", causa);
    }

    private RuntimeException tradotto(String operazione, Throwable causa) {
        log.warn("[{}] fallback su {}: {}", SERVIZIO, operazione, causa.toString());

        if (causa instanceof CallNotPermittedException) {
            return new ServizioNonDisponibileException(SERVIZIO,
                    "circuito aperto, la chiamata non e' stata nemmeno tentata", causa);
        }
        if (causa instanceof RuntimeException gia) {
            return gia;
        }
        return new ServizioNonDisponibileException(SERVIZIO, causa.toString(), causa);
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
