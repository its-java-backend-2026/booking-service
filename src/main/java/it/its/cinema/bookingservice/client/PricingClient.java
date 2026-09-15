package it.its.cinema.bookingservice.client;

import java.math.BigDecimal;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import it.its.cinema.bookingservice.client.dto.QuoteRequestJson;
import it.its.cinema.bookingservice.client.dto.QuoteResponseJson;
import it.its.cinema.bookingservice.domain.CustomerType;
import it.its.cinema.bookingservice.domain.ServizioNonDisponibileException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * PASSO 6.9 — IL GATEWAY VERSO pricing-service.
 *
 * Piu' corto di ShowsClient, e la ragione e' interessante: pricing non ha
 * risorse da non trovare e non ha stato da mettere in conflitto. Non esiste
 * un 404 sensato ("il prezzo non c'e'") e non esiste un 409. Restano due
 * casi soli: o risponde, o non risponde.
 *
 * ---------------------------------------------------------------------------
 * PASSO 7.5 — IL FALLBACK C'E', E NON INVENTA UN PREZZO.
 *
 * Era stato annunciato al G6 e la promessa e' mantenuta. La tentazione,
 * quando pricing non risponde, e' "usiamo il prezzo base e andiamo avanti":
 * il sistema resterebbe in piedi, nessuno vedrebbe un errore, e venderemmo
 * biglietti all'importo sbagliato. Lo scopriremmo settimane dopo, in
 * contabilita', senza piu' sapere quali righe sono quelle buone.
 *
 * UN FALLBACK CHE MENTE E' PEGGIO DI UN ERRORE. L'errore lo vedono tutti
 * subito; il dato sbagliato non lo vede nessuno, e resta.
 *
 * Il passo 7.5 dice cosa e' invece accettabile: un valore in cache di cui si
 * conosce l'eta', una risposta parziale dichiarata tale, o un 503 chiaro.
 * Qui il prezzo non si puo' cachare (dipende da categoria e orario) e non
 * esiste mezza risposta: resta il 503. Meglio una prenotazione non fatta che
 * una fatta male.
 *
 * Il fallback allora serve a due cose sole, ed e' gia' molto: logga la causa
 * VERA (altrimenti un errore di deserializzazione sembrerebbe per sempre "il
 * servizio e' giu'") e traduce il circuito aperto in un 503 invece che in un
 * 500. Vedi il commento esteso in ShowsClient.
 *
 * E QUI IL RETRY C'E' (passo 7.3): /prices/quote e' un CALCOLO PURO. Non
 * scrive niente, non consuma niente, e chiamarlo dieci volte da dieci volte
 * la stessa risposta. E' il caso in cui ritentare e' gratis — l'opposto
 * esatto di POST /shows/{id}/reserve.
 * ---------------------------------------------------------------------------
 */
@Component
@Slf4j
public class PricingClient {

    private static final String SERVIZIO = "pricing-service";

    private final RestClient http;

    public PricingClient(@Qualifier("pricingRestClient") RestClient http) {
        this.http = http;
    }

    /**
     * PASSO 2 DELLA SAGA — il prezzo di UN biglietto.
     *
     * customerType viaggia come stringa (il .name() dell'enum): sul confine
     * passa il nome, non il tipo. Vedi QuoteRequestJson.
     */
    @CircuitBreaker(name = "pricing", fallbackMethod = "nonDisponibile")
    @Retry(name = "pricing")
    public BigDecimal prezzoUnitario(BigDecimal basePrice, CustomerType tipo, boolean serale) {
        try {
            QuoteResponseJson risposta = http.post()
                    .uri("/prices/quote")
                    .body(new QuoteRequestJson(basePrice, tipo.name(), serale))
                    .retrieve()
                    .onStatus(HttpStatusCode::is5xxServerError,
                            (richiesta, res) -> {
                                throw new ServizioNonDisponibileException(SERVIZIO,
                                        "ha risposto " + res.getStatusCode());
                            })
                    .body(QuoteResponseJson.class);

            // Un 200 con corpo vuoto, o con un JSON in cui unitPrice manca.
            // Non e' teoria: capita quando l'altro servizio cambia il nome
            // del campo. Senza questo controllo il null arriverebbe fino al
            // database e diventerebbe una violazione di NOT NULL, cioe' un
            // 500 che parla di una colonna invece che del vero colpevole.
            if (risposta == null || risposta.unitPrice() == null) {
                throw new ServizioNonDisponibileException(SERVIZIO,
                        "ha risposto senza unitPrice");
            }
            return risposta.unitPrice();

        } catch (ResourceAccessException e) {
            // Connessione rifiutata, host irraggiungibile o timeout del
            // passo 6.6: non c'e' nessuna risposta, quindi nessun onStatus
            // e' scattato. Vedi il commento in ShowsClient.eseguendo.
            log.warn("{} non raggiungibile: {}", SERVIZIO, e.getMessage());
            throw new ServizioNonDisponibileException(SERVIZIO, e.getMessage(), e);
        }
    }

    /**
     * PASSO 7.5 — il fallback onesto: nessun prezzo inventato, un 503 vero.
     *
     * La firma e' quella del metodo protetto piu' un parametro Throwable in
     * fondo: e' cosi' che Resilience4j lo riconosce. Sbagliarla di un tipo
     * non produce nessun errore di compilazione — produce un
     * NoSuchMethodException a runtime, la prima volta che il servizio va
     * giu', cioe' nel momento peggiore. Vale la pena di avere un test che ci
     * passi davvero dentro.
     */
    private BigDecimal nonDisponibile(BigDecimal basePrice, CustomerType tipo,
                                      boolean serale, Throwable causa) {

        // La causa VERA, prima di qualsiasi altra cosa: da qui in poi e'
        // perduta. E' la nota del passo 7.5.
        log.warn("[{}] fallback su POST /prices/quote (base {}, {}, serale={}): {}",
                SERVIZIO, basePrice, tipo, serale, causa.toString());

        if (causa instanceof CallNotPermittedException) {
            throw new ServizioNonDisponibileException(SERVIZIO,
                    "circuito aperto, la chiamata non e' stata nemmeno tentata", causa);
        }
        // Tutto il resto sale intatto: un bug nostro resta un 500 (passo 6.9).
        if (causa instanceof RuntimeException gia) {
            throw gia;
        }
        throw new ServizioNonDisponibileException(SERVIZIO, causa.toString(), causa);
    }
}
