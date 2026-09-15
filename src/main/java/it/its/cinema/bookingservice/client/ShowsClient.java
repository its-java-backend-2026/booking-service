package it.its.cinema.bookingservice.client;

import java.util.function.Supplier;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import it.its.cinema.bookingservice.client.dto.SeatsJson;
import it.its.cinema.bookingservice.client.dto.ShowJson;
import it.its.cinema.bookingservice.domain.PostiEsauritiException;
import it.its.cinema.bookingservice.domain.ServizioNonDisponibileException;
import it.its.cinema.bookingservice.domain.SpettacoloNonTrovatoException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * PASSO 6.9 — IL GATEWAY VERSO shows-service, E LA TRADUZIONE DEGLI ERRORI.
 *
 * Si chiama gateway e non "client" per caso: e' il punto in cui il mondo di
 * fuori (codici HTTP, timeout, JSON) diventa il nostro (eccezioni di
 * dominio). Da qui in avanti, nel service e nel controller, non compare piu'
 * nessun numero di stato.
 *
 * ===========================================================================
 * LA TABELLA DI TRADUZIONE DEL PASSO 6.9, E IL CASO CHE NON C'E' SCRITTO
 *
 *   404 di shows        ->  SpettacoloNonTrovatoException  ->  404 nostro
 *   409 di shows        ->  PostiEsauritiException         ->  409 nostro
 *   5xx / timeout       ->  ServizioNonDisponibile         ->  503 nostro
 *
 *   ALTRI 4xx (400, 415, 422) -> NON TRADOTTI, e finiscono in 500.
 *
 * L'ultima riga e' la piu' istruttiva. Un 400 da shows-service significa che
 * SIAMO NOI ad aver mandato una richiesta sbagliata: campo mancante, tipo
 * errato, contratto disallineato dopo un rilascio. E' un bug nostro, e un bug
 * nostro e' un 500 — con lo stack trace nei log, dove serve.
 *
 * Tradurlo in 503 sarebbe l'errore piu' costoso di tutto il passo: nasconde
 * un nostro difetto dietro "il servizio a valle e' giu'", e al G7 farebbe
 * pure scattare retry e circuit breaker su un problema che riprovare non
 * risolvera' mai.
 * ===========================================================================
 *
 * ===========================================================================
 * PASSI 7.4 e 7.3 — IL BREAKER SU TUTTO, IL RETRY SOLO DOVE E' SICURO.
 *
 * Guardare quali metodi hanno @Retry e quali no, perche' e' la decisione piu'
 * importante del G7 e non si legge in nessuna configurazione:
 *
 *   perId()     GET, una lettura.                @CircuitBreaker + @Retry
 *   riserva()   POST che SCALA dei posti.        @CircuitBreaker + @Retry (dal G8)
 *   rilascia()  POST che RIMETTE dei posti.      @CircuitBreaker + @Retry (dal G8)
 *
 * La regola e' l'IDEMPOTENZA, non "e' una GET". Ripetere una lettura non
 * cambia niente da nessuna parte; ripetere una riserva — fino al G7 —
 * scalava i posti una seconda volta, e il caso in cui il retry scatta era
 * esattamente quello in cui il danno era piu' probabile: il TIMEOUT. Timeout
 * non vuol dire "non e' arrivata": vuol dire "non so se e' arrivata". Il piu'
 * delle volte lo spettacolo ha gia' scalato i posti e sta solo rispondendo
 * piano.
 *
 * ---------------------------------------------------------------------------
 * PASSO 8.3 — E OGGI QUEL RETRY SI ACCENDE, COME ERA STATO SCRITTO AL G7.
 *
 * shows-service ha la sua tabella show_operations con UNIQUE (saga_id,
 * operation_type): la seconda chiamata con lo stesso sagaId non fa niente e
 * risponde come la prima. Ripetere e' diventato gratis, quindi il retry e'
 * diventato sicuro — e le due righe di @Retry qui sotto sono tutto cio' che
 * serviva ad accenderlo.
 *
 * Vale la pena fermarsi un secondo su cosa e' successo davvero: NON abbiamo
 * cambiato idea sulla regola. La regola e' sempre "si ritenta cio' che e'
 * idempotente". E' cambiato il fatto che adesso lo e' — e il lavoro per
 * renderlo tale e' stato fatto dall'ALTRA parte del filo. Un retry non si
 * accende perche' fa comodo a chi chiama: si accende quando chi risponde
 * puo' sostenerlo.
 * ---------------------------------------------------------------------------
 *
 * Il BREAKER invece sta su tutti e tre da sempre, e non ha mai avuto lo
 * stesso problema: non riesegue niente, si limita a non tentare. Rifiutarsi
 * di chiamare non ha mai effetti collaterali.
 * ===========================================================================
 */
@Component
@Slf4j
public class ShowsClient {

    private static final String SERVIZIO = "shows-service";

    private final RestClient http;

    /**
     * @Qualifier e non il solo tipo: di RestClient ce ne sono DUE nel
     * contesto (l'altro e' quello di pricing). Senza, l'avvio fallisce con
     * NoUniqueBeanDefinitionException. Spring risolverebbe anche per nome del
     * parametro, ma quello e' un legame che si rompe rinominando una
     * variabile — e nessun compilatore avviserebbe.
     */
    public ShowsClient(@Qualifier("showsRestClient") RestClient http) {
        this.http = http;
    }

    /**
     * PASSO 1 DELLA SAGA — i dati dello spettacolo: prezzo base, orario,
     * titolo del film.
     *
     * RITENTABILE: e' una lettura, ripeterla non cambia niente da nessuna
     * parte. E' anche il punto in cui il retry rende di piu', perche' qui
     * non abbiamo ancora toccato niente: se il secondo tentativo riesce,
     * l'utente non si accorge di aver rischiato un 503.
     */
    @CircuitBreaker(name = "shows", fallbackMethod = "letturaNonDisponibile")
    @Retry(name = "shows")
    public ShowJson perId(Long showId) {
        return eseguendo("GET /shows/" + showId, () -> http.get()
                .uri("/shows/{id}", showId)
                .retrieve()
                .onStatus(stato -> stato.value() == HttpStatus.NOT_FOUND.value(),
                        (richiesta, risposta) -> {
                            throw new SpettacoloNonTrovatoException(showId);
                        })
                .onStatus(HttpStatusCode::is5xxServerError,
                        (richiesta, risposta) -> {
                            throw new ServizioNonDisponibileException(SERVIZIO,
                                    "ha risposto " + risposta.getStatusCode());
                        })
                .body(ShowJson.class));
    }

    /**
     * PASSO 3 DELLA SAGA — la riserva vera e propria.
     *
     * Non restituisce niente di utile: la risposta e' lo Show aggiornato, ma
     * a noi serve solo sapere che e' andata bene. Cio' che conta e' che
     * questo metodo o ritorna, o solleva: non esiste un "forse".
     *
     * PASSO 8.3 — E DAL G8 HA IL @Retry, che al G7 non poteva avere.
     *
     * Questo POST scala dei posti in un altro servizio. Fino a ieri
     * shows-service non riconosceva un sagaId gia' visto, quindi ritentarlo
     * dopo un timeout scalava i posti una seconda volta; da oggi la seconda
     * chiamata non fa niente (show_operations, V5) e il retry e' sicuro.
     *
     * E' il debito del G7 che viene ripagato: la riga di codice e' una sola,
     * ma e' arrivata dopo una migrazione, una tabella e una transazione
     * nell'altro servizio.
     */
    @CircuitBreaker(name = "shows", fallbackMethod = "riservaNonDisponibile")
    @Retry(name = "shows")
    public void riserva(Long showId, int quantita, String sagaId) {
        eseguendo("POST /shows/" + showId + "/reserve", () -> http.post()
                .uri("/shows/{id}/reserve", showId)
                .body(new SeatsJson(sagaId, quantita))
                .retrieve()
                .onStatus(stato -> stato.value() == HttpStatus.NOT_FOUND.value(),
                        (richiesta, risposta) -> {
                            throw new SpettacoloNonTrovatoException(showId);
                        })
                // 409: i posti sono finiti fra la lettura del passo 1 e ora.
                // Non e' un caso raro da ignorare, e' IL caso: fra le due
                // chiamate c'e' una finestra in cui chiunque puo' comprare.
                .onStatus(stato -> stato.value() == HttpStatus.CONFLICT.value(),
                        (richiesta, risposta) -> {
                            throw new PostiEsauritiException(showId, quantita);
                        })
                .onStatus(HttpStatusCode::is5xxServerError,
                        (richiesta, risposta) -> {
                            throw new ServizioNonDisponibileException(SERVIZIO,
                                    "ha risposto " + risposta.getStatusCode());
                        })
                .toBodilessEntity());
    }

    /**
     * LA COMPENSAZIONE del passo 3: rimette i posti a disposizione.
     *
     * Scritta al G6 e rimasta senza chiamanti per due giornate, di proposito:
     * il passo 6.10 si fermava prima, e il buco che restava era il problema
     * del G8. Da oggi la chiama BookingSaga.compensa (passo 8.7), ed e'
     * bastata una riga di orchestrazione perche' il metodo era gia' qui.
     *
     * Nota: non solleva PostiEsauriti (rilasciare non puo' esaurire niente)
     * e ignora il 404 con la stessa logica di tutte le compensazioni: se lo
     * spettacolo non c'e' piu', non c'e' niente da rimettere a posto.
     *
     * PASSO 8.3 — come riserva(), ha il @Retry dal G8: "rilascia 2 posti"
     * eseguito due volte ne rilascia 2, non 4. Ed e' il caso in cui ripetere
     * si nota MENO — dei posti in regalo non fanno arrabbiare nessuno subito.
     */
    @CircuitBreaker(name = "shows", fallbackMethod = "rilascioNonDisponibile")
    @Retry(name = "shows")
    public void rilascia(Long showId, int quantita, String sagaId) {
        eseguendo("POST /shows/" + showId + "/release", () -> http.post()
                .uri("/shows/{id}/release", showId)
                .body(new SeatsJson(sagaId, quantita))
                .retrieve()
                .onStatus(HttpStatusCode::is5xxServerError,
                        (richiesta, risposta) -> {
                            throw new ServizioNonDisponibileException(SERVIZIO,
                                    "ha risposto " + risposta.getStatusCode());
                        })
                .toBodilessEntity());
    }

    // ---------------------------------------------------------------------
    // PASSO 7.5 — I FALLBACK, E SONO ONESTI.
    //
    // Nessuno di questi inventa niente. Non esiste un valore ragionevole da
    // restituire al posto di uno spettacolo che non sappiamo leggere: un
    // titolo finto finirebbe stampato sul biglietto, e un prezzo finto
    // finirebbe in contabilita'. L'unica risposta vera e' "ora non si puo'",
    // che per il passo 6.9 e' un 503.
    //
    // Allora a cosa servono, se non cambiano l'esito? A due cose che senza
    // di loro non ci sarebbero:
    //
    //   1. IL LOG DELLA CAUSA VERA. Il fallback cattura QUALSIASI eccezione,
    //      quindi maschera cio' che e' successo davvero. Senza il
    //      causa.toString() qui sotto, un banale errore di deserializzazione
    //      sembrerebbe per sempre "il servizio e' giu'" — ed e' la nota del
    //      passo 7.5, quella che fa perdere i pomeriggi.
    //
    //   2. LA TRADUZIONE DI CallNotPermittedException. Quando il circuito e'
    //      aperto, Resilience4j non chiama nessuno e solleva un'eccezione
    //      SUA, che il GestoreErrori non conosce: senza fallback diventerebbe
    //      un 500 — cioe' "abbiamo un bug" proprio mentre il sistema si sta
    //      difendendo esattamente come gli abbiamo chiesto.
    // ---------------------------------------------------------------------

    private ShowJson letturaNonDisponibile(Long showId, Throwable causa) {
        throw tradotto("GET /shows/" + showId, causa);
    }

    private void riservaNonDisponibile(Long showId, int quantita, String sagaId, Throwable causa) {
        throw tradotto("POST /shows/" + showId + "/reserve [saga " + sagaId + "]", causa);
    }

    private void rilascioNonDisponibile(Long showId, int quantita, String sagaId, Throwable causa) {
        throw tradotto("POST /shows/" + showId + "/release [saga " + sagaId + "]", causa);
    }

    /**
     * Cosa fa un fallback che non inventa niente.
     *
     * Il circuito aperto diventa un 503 come tutti gli altri guasti a valle:
     * per chi chiama e' la stessa identica situazione ("ora non si puo',
     * riprova fra poco"), e il MOTIVO — che stavolta non abbiamo nemmeno
     * provato — sta nel log e in /actuator/health, dove serve.
     *
     * Tutto il resto risale INTATTO, ed e' la meta' che si sbaglia piu'
     * spesso: un 404 deve restare un 404 e un bug nostro deve restare un 500.
     * Un fallback che trasformasse tutto in 503 renderebbe invisibile ogni
     * nostro errore di contratto (passo 6.9) dietro "e' giu' qualcun altro".
     */
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

    /**
     * L'involucro che cattura cio' che NON e' una risposta HTTP.
     *
     * ResourceAccessException e' la classe con cui Spring avvolge le
     * IOException del livello di trasporto, e sotto ci stanno tre situazioni
     * che per noi sono la stessa: connessione rifiutata (il servizio non
     * c'e'), host irraggiungibile (la rete non c'e'), e il TIMEOUT del passo
     * 6.6 (c'e', ma non risponde in tempo).
     *
     * In tutti e tre i casi non abbiamo una risposta, quindi nessun onStatus
     * scatta: senza questo try/catch l'eccezione salirebbe fino al catch-all
     * di GestoreErrori e diventerebbe un 500 — cioe' racconterebbe come
     * nostro bug il fatto che qualcun altro non ha risposto.
     */
    private <T> T eseguendo(String operazione, Supplier<T> chiamata) {
        try {
            return chiamata.get();
        } catch (ResourceAccessException e) {
            log.warn("{} non raggiungibile su {}: {}", SERVIZIO, operazione, e.getMessage());
            throw new ServizioNonDisponibileException(SERVIZIO, e.getMessage(), e);
        }
    }
}
