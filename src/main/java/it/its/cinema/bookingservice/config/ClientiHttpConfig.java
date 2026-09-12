package it.its.cinema.bookingservice.config;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * PASSO 6.6 — UN RestClient PER OGNI SERVIZIO A VALLE, CON TIMEOUT ESPLICITI.
 *
 * Due bean e non uno condiviso, per due ragioni concrete:
 *
 *  1. OGNUNO HA IL SUO INDIRIZZO. Un RestClient con baseUrl fissato evita
 *     che gli URL assoluti si spargano per il codice dei gateway.
 *  2. DAL G7 OGNUNO AVRA' LA SUA POLITICA. Circuit breaker e retry si
 *     configurano per servizio: pricing e' ritentabile (e' puro), payment
 *     non lo sara' mai. Con un client solo, quella distinzione non si
 *     potrebbe nemmeno esprimere.
 *
 * ===========================================================================
 * PASSO 6.6 — PERCHE' I TIMEOUT NON SONO "TUNING"
 *
 * Senza timeout espliciti, l'HttpClient del JDK aspetta per SEMPRE.
 *
 * Il ragionamento che conta: un servizio LENTO fa piu' danni di un servizio
 * SPENTO. Spento risponde subito "connessione rifiutata" e noi ce ne
 * liberiamo in millisecondi. Lento tiene occupato un nostro thread per tutta
 * la durata dell'attesa; con abbastanza richieste in arrivo, i thread del
 * nostro pool finiscono tutti li' dentro e NOI smettiamo di rispondere —
 * anche alle richieste che con quel servizio non c'entrano niente.
 * E' cosi' che un guasto di uno diventa un guasto di tutti.
 *
 * DUE timeout, e sono due domande diverse:
 *
 *   connectTimeout  quanto aspetto che la connessione TCP si apra.
 *                   Corto (2s): o l'altro c'e' sulla rete, o non c'e'.
 *
 *   readTimeout     quanto aspetto la risposta DOPO aver chiesto.
 *                   Piu' lungo (3s): dall'altra parte c'e' del lavoro vero.
 *
 * Il readTimeout si sceglie guardando il tempo di risposta reale del servizio
 * a valle: deve stare sopra il suo caso peggiore normale e sotto la pazienza
 * del nostro chiamante. Tre secondi qui e' un numero da aula; in esercizio si
 * legge dalle metriche (il 99esimo percentile) e si rivede quando cambia.
 * ===========================================================================
 */
@Configuration
public class ClientiHttpConfig {

    /** Quanto aspettare che si apra la connessione: o c'e' o non c'e'. */
    private static final Duration TIMEOUT_CONNESSIONE = Duration.ofSeconds(2);

    /** Quanto aspettare la risposta dopo aver chiesto. */
    private static final Duration TIMEOUT_LETTURA = Duration.ofSeconds(3);

    /**
     * Il client verso shows-service.
     *
     * RestClient.Builder e' un bean PROTOTYPE: ogni metodo che lo chiede ne
     * riceve uno nuovo. E' il motivo per cui i due @Bean qui sotto possono
     * configurarlo ognuno a modo suo senza pestarsi i piedi — con un bean
     * singleton, il secondo baseUrl sovrascriverebbe il primo.
     */
    @Bean
    RestClient showsRestClient(RestClient.Builder builder,
                               @Value("${cinema.shows.url}") String baseUrl) {
        return builder
                .baseUrl(baseUrl)
                .requestFactory(fabbricaConTimeout())
                .build();
    }

    /** Il client verso pricing-service. */
    @Bean
    RestClient pricingRestClient(RestClient.Builder builder,
                                 @Value("${cinema.pricing.url}") String baseUrl) {
        return builder
                .baseUrl(baseUrl)
                .requestFactory(fabbricaConTimeout())
                .build();
    }

    /**
     * La fabbrica di richieste, con i due timeout e una versione di HTTP
     * scelta a mano.
     *
     * =======================================================================
     * PASSO 6.7 — PERCHE' HTTP/1.1 ESPLICITO
     *
     * L'HttpClient del JDK negozia HTTP/2 di default. Con alcuni server la
     * negoziazione fallisce, e fallisce male:
     *
     *     Received RST_STREAM: Protocol error
     *
     * un messaggio che non nomina ne' HTTP/2 ne' la negoziazione, e che manda
     * a cercare un bug nel codice dell'applicazione. Succede con WireMock
     * (i test a contratto del G10) e con proxy aziendali datati.
     *
     * Fra i nostri servizi HTTP/2 non porterebbe comunque nessun vantaggio
     * misurabile: le chiamate sono poche, piccole e in sequenza, e il
     * multiplexing serve a tutt'altro. Una riga, e un'intera categoria di
     * guasti incomprensibili sparisce.
     * =======================================================================
     */
    private static ClientHttpRequestFactory fabbricaConTimeout() {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(TIMEOUT_CONNESSIONE)
                .build();

        // Il readTimeout NON sta sull'HttpClient: sta sulla fabbrica, perche'
        // nel JDK e' un parametro della singola richiesta e non della
        // connessione. E' il motivo per cui servono due oggetti.
        JdkClientHttpRequestFactory fabbrica = new JdkClientHttpRequestFactory(httpClient);
        fabbrica.setReadTimeout(TIMEOUT_LETTURA);
        return fabbrica;
    }
}
