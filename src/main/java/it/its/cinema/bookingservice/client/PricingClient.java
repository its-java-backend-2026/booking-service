package it.its.cinema.bookingservice.client;

import java.math.BigDecimal;

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
 * IL FALLBACK CHE QUI NON C'E', E CHE AL G7 NON CI SARA' COMUNQUE.
 *
 * La tentazione, quando pricing non risponde, e' "usiamo il prezzo base e
 * andiamo avanti". E' la scorciatoia che il passo 7.5 vieta esplicitamente:
 * venderemmo biglietti al prezzo sbagliato, e lo scopriremmo in contabilita'
 * settimane dopo. Meglio una prenotazione non fatta che una fatta male.
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
}
