package it.its.cinema.bookingservice.service;

import it.its.cinema.bookingservice.domain.Booking;
import it.its.cinema.bookingservice.domain.PassoSaga;
import it.its.cinema.bookingservice.domain.SagaState;
import it.its.cinema.bookingservice.repository.BookingRepository;
import it.its.cinema.bookingservice.repository.SagaStateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * PASSO 8.6 — LE SCRITTURE LOCALI DELLA SAGA, IN UNA CLASSE A PARTE.
 *
 * ===========================================================================
 * QUESTA CLASSE ESISTE PER DUE REGOLE, E VIOLARLE NON DA' NESSUN ERRORE.
 *
 * 1. @Transactional FUNZIONA SOLO ATTRAVERSO IL PROXY.
 *
 *    Se questi metodi fossero metodi privati di BookingSaga — o metodi
 *    pubblici chiamati da un altro metodo della stessa classe — la chiamata
 *    non passerebbe dal proxy di Spring e NON APRIREBBE NESSUNA TRANSAZIONE.
 *    In silenzio: il codice compila, i test con i mock passano, e in
 *    esercizio due scritture che dovevano essere atomiche non lo sono.
 *
 *    E' la self-invocation, lo stesso avvertimento che ShowService porta in
 *    cima dal G3. Metterle in un bean diverso rende la regola impossibile da
 *    violare per distrazione.
 *
 * 2. UNA TRANSAZIONE NON DEVE MAI RESTARE APERTA DURANTE UNA CHIAMATA DI RETE.
 *
 *    E' la ragione piu' importante delle due. Se BookingSaga fosse
 *    @Transactional, la transazione — e quindi una connessione del pool —
 *    resterebbe occupata per tutta la durata di TRE chiamate HTTP: fino a
 *    nove secondi con i timeout del passo 6.6. Bastano una decina di
 *    prenotazioni lente insieme per esaurire il pool, e a quel punto anche
 *    le GET smettono di rispondere.
 *
 *    Tenendo le transazioni QUI, ognuna dura quanto una UPDATE: si apre dopo
 *    che la chiamata di rete e' finita e si chiude prima della successiva.
 *
 * E c'e' una conseguenza che va accettata in chiaro: fra un passo e il
 * successivo NON c'e' nessuna atomicita'. Se il processo muore fra
 * "riserva i posti" e "scrivi POSTI_RISERVATI", la riga dice ancora AVVIATA
 * mentre i posti sono gia' scalati. E' il prezzo di non avere transazioni
 * distribuite, e la saga lo paga cosi': lo stato si scrive DOPO il passo,
 * quindi nel dubbio si sa di aver fatto MENO di quello che risulta — e una
 * compensazione in meno e' un guaio che si vede (posti bloccati), mentre una
 * compensazione di troppo sarebbe un rimborso mai dovuto.
 * ===========================================================================
 */
@Component
@RequiredArgsConstructor
public class SagaStore {

    private final BookingRepository prenotazioni;
    private final SagaStateRepository saghe;

    /**
     * PASSO 8.4 — l'apertura: la prenotazione IN_CORSO e la saga AVVIATA.
     *
     * Le due righe nascono nella stessa transazione, e devono: una
     * prenotazione senza la sua saga sarebbe una riga che nessuno sapra' mai
     * come chiudere, e una saga senza prenotazione non avrebbe nemmeno un
     * booking_id da scrivere (la FOREIGN KEY della V3 lo impedisce).
     *
     * L'ordine e' obbligato: prima la prenotazione, perche' il suo id — che
     * lo assegna il database — e' quello che serve alla saga e, subito dopo,
     * a payment-service.
     *
     * ATTENZIONE: qui puo' saltare il vincolo UNIQUE su idempotency_key
     * (passo 7.6). L'eccezione NON si cattura in questo metodo: dentro la
     * transazione sarebbe un vicolo cieco — la violazione la marca
     * rollback-only e la rilettura morirebbe al commit. La gestisce
     * BookingService, che non e' transazionale e puo' rileggere il mondo
     * com'e' rimasto.
     */
    @Transactional
    public AperturaSaga apri(Booking nuova, String customerId, int puntiFedelta) {
        Booking prenotazione = prenotazioni.saveAndFlush(nuova);

        SagaState saga = saghe.save(new SagaState(
                prenotazione.getId(),
                customerId,
                prenotazione.getShowId(),
                prenotazione.getQuantity(),
                puntiFedelta));

        return new AperturaSaga(prenotazione, saga);
    }

    /**
     * PASSO 8.5 — un passo e' andato a buon fine: si scrive SUBITO.
     *
     * Si rilegge dentro la transazione invece di salvare l'oggetto che arriva
     * da fuori. Costa una SELECT e toglie di mezzo una categoria intera di
     * sorprese: l'oggetto che gira per l'orchestratore e' DETACHED — la sua
     * transazione si e' chiusa da un pezzo — e salvarlo direttamente
     * riscriverebbe tutte le sue colonne com'erano quando e' stato letto,
     * comprese quelle che nel frattempo potrebbe aver cambiato qualcun altro.
     */
    @Transactional
    public SagaState avanza(SagaState saga, PassoSaga passo) {
        SagaState corrente = saghe.findById(saga.getId()).orElseThrow();
        corrente.avanza(passo);
        return saghe.save(corrente);
    }

    /**
     * PASSO 8.5 — la conclusione felice: prenotazione CONFERMATA, saga
     * COMPLETATA.
     *
     * Le due scritture stanno insieme perche' dicono la stessa cosa: da
     * questo momento esiste un biglietto. Una prenotazione confermata con la
     * sua saga ancora IN_CORSO sarebbe una riga che un lavoro periodico
     * proverebbe a "riprendere" — cioe' a compensare un acquisto riuscito.
     */
    @Transactional
    public Booking conferma(Booking prenotazione, SagaState saga) {
        SagaState correnteSaga = saghe.findById(saga.getId()).orElseThrow();
        correnteSaga.completata();
        saghe.save(correnteSaga);

        Booking corrente = prenotazioni.findById(prenotazione.getId()).orElseThrow();
        corrente.conferma();
        return prenotazioni.save(corrente);
    }

    /**
     * PASSO 8.5 — la prenotazione e' fallita.
     *
     * La riga resta, con lo stato FALLITA. Vedi Booking.fallisci.
     */
    @Transactional
    public Booking fallisci(Booking prenotazione) {
        Booking corrente = prenotazioni.findById(prenotazione.getId()).orElseThrow();
        corrente.fallisci();
        return prenotazioni.save(corrente);
    }

    /**
     * PASSO 8.7 — com'e' andata la compensazione.
     *
     * Il booleano decide fra COMPENSATA e COMPENSAZIONE_PARZIALE, e la
     * seconda e' l'unica delle due che qualcuno dovra' guardare a mano.
     */
    @Transactional
    public SagaState compensata(SagaState saga, boolean completa, String errore) {
        SagaState corrente = saghe.findById(saga.getId()).orElseThrow();
        corrente.compensata(completa, errore);
        return saghe.save(corrente);
    }
}
