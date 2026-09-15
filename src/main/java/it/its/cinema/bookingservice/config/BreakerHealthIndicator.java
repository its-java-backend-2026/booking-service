package it.its.cinema.bookingservice.config;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * ===========================================================================
 * PASSO 7.7 — LO STATO DEI BREAKER DENTRO /actuator/health.
 *
 * ATTENZIONE, E' UNA TRAPPOLA DA BOOT 4, DELLA STESSA FAMIGLIA DEL PASSO 6.5.
 *
 * La configurazione del passo 7.2 dice registerHealthIndicator: true, e in
 * Spring Boot 3 sarebbe finito tutto qui: resilience4j-spring-boot3 porta un
 * CircuitBreakersHealthIndicator e lo registra da solo.
 *
 * In Boot 4 le classi della salute hanno cambiato casa —
 *     org.springframework.boot.actuate.health.HealthIndicator
 *  -> org.springframework.boot.health.contributor.HealthIndicator
 * — e quell'auto-configurazione, compilata contro i nomi vecchi, viene
 * scartata in silenzio all'avvio. NESSUN errore, nessun avviso: semplicemente
 * in /actuator/health non compare la sezione circuitBreakers, e chi la cerca
 * conclude che il breaker non e' configurato. E' falso: il breaker funziona
 * benissimo, si vede in /actuator/circuitbreakers, ed e' solo la vetrina a
 * mancare.
 *
 * Venti righe la rimettono, scritte contro l'API di Boot 4.
 * ===========================================================================
 *
 * UP ANCHE CON UN CIRCUITO APERTO, ed e' la decisione da discutere.
 *
 * La tentazione e' rispondere DOWN: sembra onesto, "qualcosa non va". E'
 * l'errore piu' costoso che si possa fare qui, perche' /actuator/health e'
 * cio' che guardano gli orchestratori:
 *
 *   - un breaker aperto racconta un guasto di QUALCUN ALTRO. Il nostro
 *     processo sta benissimo: risponde, legge il database, e GET /bookings
 *     funziona perfettamente (i dati sono nostri, passo 6.3);
 *   - dichiarandoci DOWN ci faremmo togliere dal bilanciatore o riavviare,
 *     e riavviarci non sistemerebbe il servizio a valle — toglierebbe anche
 *     le parti di noi che funzionavano. Il guasto di uno diventerebbe il
 *     guasto di tutti, che e' esattamente cio' che il breaker esiste per
 *     impedire.
 *
 * Lo stato quindi si MOSTRA e non si SUBISCE: chi opera lo legge, nessun
 * automatismo ci spegne per colpa sua. E' la stessa scelta della readiness in
 * application.yaml, vista da un'altra porta.
 */
@Component("circuitBreakers")
public class BreakerHealthIndicator implements HealthIndicator {

    private final CircuitBreakerRegistry registro;

    public BreakerHealthIndicator(CircuitBreakerRegistry registro) {
        this.registro = registro;
    }

    @Override
    public Health health() {
        Map<String, Object> dettagli = new LinkedHashMap<>();

        for (CircuitBreaker breaker : registro.getAllCircuitBreakers()) {
            var metriche = breaker.getMetrics();
            dettagli.put(breaker.getName(), Map.of(
                    "state", breaker.getState().name(),
                    "failureRate", metriche.getFailureRate() < 0
                            ? "n/d"   // sotto minimumNumberOfCalls non c'e' ancora un tasso
                            // Locale.ROOT: senza, su una macchina italiana
                            // esce "100,0%" e chi legge i log da un'altra
                            // parte del mondo trova un separatore diverso
                            // dal suo. I numeri per le macchine non hanno
                            // nazionalita'.
                            : String.format(Locale.ROOT, "%.1f%%", metriche.getFailureRate()),
                    "bufferedCalls", metriche.getNumberOfBufferedCalls(),
                    "failedCalls", metriche.getNumberOfFailedCalls(),
                    // Le chiamate che NON sono state fatte perche' il circuito
                    // era aperto: e' il numero che dice quanto lavoro inutile
                    // ci siamo risparmiati, e quanti utenti hanno avuto un 503.
                    "notPermittedCalls", metriche.getNumberOfNotPermittedCalls()));
        }

        return Health.up().withDetails(dettagli).build();
    }
}
