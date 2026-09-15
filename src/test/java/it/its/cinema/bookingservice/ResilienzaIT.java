package it.its.cinema.bookingservice;

import java.math.BigDecimal;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import it.its.cinema.bookingservice.client.PricingClient;
import it.its.cinema.bookingservice.client.ShowsClient;
import it.its.cinema.bookingservice.domain.CustomerType;
import it.its.cinema.bookingservice.domain.ServizioNonDisponibileException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ===========================================================================
 * PASSI 7.2, 7.3, 7.4 e 7.5 — LA PROVA CHE LA RESILIENZA E' ACCESA DAVVERO.
 *
 * E' un IT e non un test unitario perche' cio' che si vuole verificare NON e'
 * Resilience4j — quello funziona — ma il nostro CABLAGGIO: che le annotazioni
 * del passo 7.4 siano state intercettate, che leggano la configurazione del
 * passo 7.2, e che i fallback del passo 7.5 abbiano la firma giusta.
 *
 * Nessuna di queste tre cose fallisce in compilazione:
 *
 *   - senza resilience4j-spring-boot3 nel pom, le annotazioni sono decorazioni
 *     e ogni chiamata va dritta al servizio a valle, sempre;
 *   - con un nome di istanza sbagliato ("princing"), Resilience4j ne crea una
 *     al volo con i valori di default, e la configurazione del passo 7.2 non
 *     la legge nessuno;
 *   - con un fallback dalla firma sbagliata di un tipo, arriva una
 *     NoSuchMethodException — la prima volta che un servizio va giu', cioe'
 *     nel momento peggiore possibile.
 *
 * Tutte e tre si scoprono solo passandoci dentro. Qui ci si passa.
 *
 * COME SI SIMULA IL GUASTO: pricing punta a localhost:1, una porta su cui non
 * ascolta nessuno. La connessione viene rifiutata SUBITO, quindi il test e'
 * veloce e non dipende da nessun timeout.
 * ===========================================================================
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        // shows sano non serve: qui si guarda solo il breaker di pricing
        "cinema.shows.url=http://localhost:1",
        // la porta 1: connessione rifiutata all'istante
        "cinema.pricing.url=http://localhost:1",

        // Il jitter e il backoff del passo 7.3 sono giusti in esercizio e
        // inutili qui: farebbero durare il test sei secondi per verificare
        // una cosa che non c'entra con l'attesa. Si azzera SOLO l'attesa,
        // non il numero di tentativi, che e' cio' che si sta verificando.
        "resilience4j.retry.instances.pricing.waitDuration=1ms",
        "resilience4j.retry.instances.pricing.enableExponentialBackoff=false",
        "resilience4j.retry.instances.pricing.enableRandomizedWait=false"
})
@DisplayName("Resilienza — il circuit breaker e il retry del G7")
class ResilienzaIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    PricingClient pricingClient;

    @Autowired
    ShowsClient showsClient;

    @Autowired
    CircuitBreakerRegistry breaker;

    @Autowired
    RetryRegistry retry;

    /**
     * I breaker sono bean del contesto, e il contesto e' condiviso fra i
     * metodi di test: senza questo reset l'esito dipenderebbe dall'ordine in
     * cui JUnit li esegue, che e' il modo piu' rapido di ottenere un test che
     * fallisce solo sulla macchina di qualcun altro.
     */
    @BeforeEach
    void circuitiChiusi() {
        breaker.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
    }

    private void chiedoUnPrezzo() {
        pricingClient.prezzoUnitario(new BigDecimal("10.00"), CustomerType.STUDENT, true);
    }

    // ---------------------------------------------------------------- 7.2

    @Test
    @DisplayName("La configurazione del passo 7.2 e' quella che i breaker stanno usando")
    void laConfigurazioneEQuellaScritta() {
        var config = breaker.circuitBreaker("pricing").getCircuitBreakerConfig();

        assertThat(config.getSlidingWindowSize()).isEqualTo(20);
        assertThat(config.getMinimumNumberOfCalls()).isEqualTo(10);
        assertThat(config.getFailureRateThreshold()).isEqualTo(50f);
        assertThat(config.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(3);

        // I quattro servizi del passo 7.2, compresi i due che arriveranno al G8
        assertThat(breaker.getAllCircuitBreakers().stream().map(CircuitBreaker::getName))
                .contains("shows", "pricing", "payment", "loyalty");
    }

    /**
     * PASSO 7.2 — la riga che evita il breaker isterico, vista funzionare.
     *
     * Nove chiamate andate male di seguito, e il circuito e' ancora CHIUSO:
     * sotto minimumNumberOfCalls il breaker non decide niente, per quanto
     * male vadano le cose. E' cio' che impedisce a due chiamate sfortunate
     * subito dopo un deploy di staccare un servizio sano.
     */
    @Test
    @DisplayName("Sotto minimumNumberOfCalls il circuito NON si apre, per quanto si fallisca")
    void sottoIlMinimoIlCircuitoRestaChiuso() {
        for (int i = 0; i < 9; i++) {
            assertThatThrownBy(this::chiedoUnPrezzo)
                    .isInstanceOf(ServizioNonDisponibileException.class);
        }

        assertThat(breaker.circuitBreaker("pricing").getState())
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }

    // ---------------------------------------------------------------- 7.4

    /**
     * IL TEST CHE VALE LA GIORNATA.
     *
     * Con pricing spento, dopo abbastanza tentativi il circuito si apre e il
     * servizio SMETTE di chiamare. Da quel momento la risposta arriva senza
     * toccare la rete: e' la differenza fra "degrada con grazia" e "cade".
     *
     * E arriva sempre come ServizioNonDisponibileException, cioe' come 503:
     * il CallNotPermittedException di Resilience4j non esce mai da qui, se lo
     * mangia il fallback del passo 7.5. Senza, sarebbe un 500 — "abbiamo un
     * bug" proprio mentre il sistema si sta difendendo come gli abbiamo
     * chiesto.
     */
    @Test
    @DisplayName("Con pricing spento il circuito si apre, e da li' in poi non si chiama piu' nessuno")
    void ilCircuitoSiApreESmetteDiChiamare() {
        for (int i = 0; i < 20; i++) {
            assertThatThrownBy(this::chiedoUnPrezzo)
                    .isInstanceOf(ServizioNonDisponibileException.class);
        }

        CircuitBreaker pricing = breaker.circuitBreaker("pricing");
        assertThat(pricing.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // A circuito aperto la chiamata non viene nemmeno tentata: si vede da
        // qui, dove il contatore delle chiamate NON PERMESSE sale.
        long nonPermesseAllInizio = pricing.getMetrics().getNumberOfNotPermittedCalls();
        assertThatThrownBy(this::chiedoUnPrezzo)
                .isInstanceOf(ServizioNonDisponibileException.class)
                .hasMessageContaining("circuito aperto");
        assertThat(pricing.getMetrics().getNumberOfNotPermittedCalls())
                .isGreaterThan(nonPermesseAllInizio);
    }

    /**
     * Un breaker PER SERVIZIO, mai uno globale (passo 7.2).
     *
     * pricing in ginocchio non deve chiudere il circuito verso shows: in
     * esercizio significa che un guasto al calcolo dei prezzi non toglie
     * anche la lettura degli spettacoli.
     */
    @Test
    @DisplayName("Il circuito di pricing aperto lascia chiuso quello di shows")
    void iBreakerSonoIndipendenti() {
        for (int i = 0; i < 20; i++) {
            assertThatThrownBy(this::chiedoUnPrezzo)
                    .isInstanceOf(ServizioNonDisponibileException.class);
        }

        assertThat(breaker.circuitBreaker("pricing").getState())
                .isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(breaker.circuitBreaker("shows").getState())
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }

    // ---------------------------------------------------------------- 7.3

    /**
     * PASSO 7.3 — il retry c'e', e ha riprovato davvero.
     *
     * Una sola chiamata dal nostro punto di vista, tre tentativi sulla rete:
     * lo dicono le metriche del RetryRegistry. E' l'unico modo di sapere che
     * l'annotazione @Retry non e' solo scritta.
     */
    @Test
    @DisplayName("Una chiamata a pricing che fallisce e' stata tentata piu' di una volta")
    void ilRetryHaRiprovato() {
        var metriche = retry.retry("pricing").getMetrics();
        long primaDelTest = metriche.getNumberOfFailedCallsWithRetryAttempt();

        assertThatThrownBy(this::chiedoUnPrezzo)
                .isInstanceOf(ServizioNonDisponibileException.class);

        // "fallita DOPO aver ritentato": se il retry non fosse attivo questo
        // contatore resterebbe fermo e salirebbe quello senza ritentativi.
        assertThat(metriche.getNumberOfFailedCallsWithRetryAttempt())
                .isGreaterThan(primaDelTest);

        assertThat(retry.retry("pricing").getRetryConfig().getMaxAttempts()).isEqualTo(3);
    }

    /**
     * ===================================================================
     * PASSO 7.3 — E LA COSA CHE NON C'E', CHE E' LA PIU' IMPORTANTE.
     *
     * Non esiste un Retry chiamato "shows" attaccato a riserva(): il retry
     * su shows sta solo sulla lettura. Ritentare POST /shows/{id}/reserve
     * dopo un timeout scalerebbe i posti una seconda volta, perche' timeout
     * non vuol dire "non e' arrivata" ma "non so se e' arrivata".
     *
     * Il test guarda il contatore dei tentativi del retry "shows" mentre si
     * chiama riserva() su un servizio spento: se qualcuno un giorno
     * aggiungesse @Retry a quel metodo — sembra un miglioramento, e in un
     * pomeriggio distratto sembra perfino ovvio — questo test lo fermerebbe.
     * ===================================================================
     */
    @Test
    @DisplayName("riserva() NON viene ritentata: ripetere una POST che scala posti e' un danno")
    void laRiservaNonSiRitenta() {
        var metriche = retry.retry("shows").getMetrics();
        long primaDelTest = metriche.getNumberOfFailedCallsWithRetryAttempt()
                + metriche.getNumberOfFailedCallsWithoutRetryAttempt();

        assertThatThrownBy(() -> showsClient.riserva(1L, 2, "saga-di-prova"))
                .isInstanceOf(ServizioNonDisponibileException.class);

        // Il retry di shows non ha visto passare NIENTE: la chiamata non e'
        // mai entrata nel suo perimetro.
        assertThat(metriche.getNumberOfFailedCallsWithRetryAttempt()
                + metriche.getNumberOfFailedCallsWithoutRetryAttempt())
                .isEqualTo(primaDelTest);

        // Il breaker invece l'ha vista: rifiutarsi di chiamare non ha
        // effetti collaterali, rieseguire si'.
        assertThat(breaker.circuitBreaker("shows").getMetrics().getNumberOfFailedCalls())
                .isGreaterThan(0);
    }
}
