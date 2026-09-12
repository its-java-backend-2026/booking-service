package it.its.cinema.bookingservice.client;

import java.util.function.Supplier;

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
     */
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
     */
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
     * Oggi (G6) NON la chiama nessuno, ed e' voluto che sia cosi': il passo
     * 6.10 si ferma prima, e il buco che resta e' il problema del G8. Il
     * metodo c'e' perche' e' la meta' mancante del contratto di reserve, e
     * perche' averlo gia' pronto rende il G8 una riga di orchestrazione
     * invece di un capitolo nuovo.
     *
     * Nota: non solleva PostiEsauriti (rilasciare non puo' esaurire niente)
     * e ignora il 404 con la stessa logica delle compensazioni: se lo
     * spettacolo non c'e' piu', non c'e' niente da rimettere a posto.
     */
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
