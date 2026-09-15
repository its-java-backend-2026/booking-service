package it.its.cinema.bookingservice.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.UUID;

import it.its.cinema.bookingservice.client.PricingClient;
import it.its.cinema.bookingservice.client.ShowsClient;
import it.its.cinema.bookingservice.client.dto.ShowJson;
import it.its.cinema.bookingservice.domain.Booking;
import it.its.cinema.bookingservice.domain.BookingNotFoundException;
import it.its.cinema.bookingservice.domain.CustomerType;
import it.its.cinema.bookingservice.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PASSO 8.5 — IL FLUSSO DI POST /bookings, CHE ORA ATTRAVERSA CINQUE PROCESSI.
 *
 *     0. l'ho gia' vista?                    (idempotenza, passo 7.6)
 *     1. GET  shows-service   /shows/{id}    prezzo base, orario, titolo
 *     2. POST pricing-service /prices/quote  prezzo unitario
 *     3. la prenotazione IN_CORSO e la saga AVVIATA               <- SagaStore
 *     4. la saga: posti, pagamento, punti                         <- BookingSaga
 *
 * ===========================================================================
 * COSA E' CAMBIATO DAL G7, E PERCHE' L'ORDINE E' DIVERSO.
 *
 * Al G6 e al G7 la riga si scriveva ALLA FINE, dopo aver riservato i posti.
 * Era il buco dichiarato in cima a questa classe per due giornate: se la
 * INSERT falliva, i posti restavano scalati e nessuno lo sapeva.
 *
 * Adesso la riga si scrive PRIMA, e cambia due cose:
 *
 *   1. IL BUCO SI CHIUDE. Da quando i posti vengono scalati esiste gia' una
 *      riga che dice che qualcuno li ha presi, e una saga che sa come
 *      rimetterli a posto. Il caso "il database rifiuta la INSERT dopo aver
 *      scalato i posti" non esiste piu': se il database rifiuta, e' prima
 *      che i posti siano toccati.
 *
 *   2. LA CORSA SULL'IDEMPOTENZA SI RISOLVE PRIMA. Il vincolo UNIQUE su
 *      idempotency_key ora scatta PRIMA di qualsiasi chiamata: due doppi
 *      clic simultanei non arrivano nemmeno a riservare i posti. Al G7 la
 *      corsa si perdeva DOPO, e il commento di allora lo diceva — "i posti
 *      riservati da questo tentativo restano scalati (compensazione al G8)".
 *      Non c'e' piu' niente da compensare, perche' non c'e' piu' niente da
 *      scalare.
 *
 * Non e' un dettaglio di ordine: e' che scrivere il fatto PRIMA di agire
 * fuori e' l'unico modo di sapere, dopo, che cosa si era cominciato.
 * ===========================================================================
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BookingService {

    private final BookingRepository repository;
    private final ShowsClient showsClient;
    private final PricingClient pricingClient;
    private final SagaStore store;
    private final BookingSaga saga;

    /**
     * QUI NON C'E' @Transactional, ED E' UNA SCELTA — ORA PER TRE MOTIVI.
     *
     * 1. Non si tiene aperta una transazione — e quindi una connessione del
     *    pool — durante delle chiamate di rete. Con i timeout del passo 6.6
     *    e i cinque servizi del G8 sarebbero fino a quindici secondi per
     *    prenotazione: bastano dieci richieste lente insieme per esaurire il
     *    pool, e a quel punto anche le GET smettono di rispondere.
     *
     * 2. La violazione del vincolo UNIQUE dev'essere trattata come una
     *    RISPOSTA (passo 7.6) e non come un errore. Dentro una transazione
     *    non si puo': la violazione la marca rollback-only, e la rilettura
     *    della riga vincente morirebbe al commit con
     *    UnexpectedRollbackException — un messaggio che non nomina nessun
     *    vincolo e manda a cercare nel posto sbagliato.
     *
     * 3. Le scritture che DEVONO essere atomiche stanno in SagaStore, che e'
     *    un bean a parte con le sue transazioni corte (passo 8.6).
     */
    public EsitoPrenotazione crea(String chiaveIdempotenza, Long showId, String customerId,
                                  CustomerType customerType, int quantita) {

        String chiave = validata(chiaveIdempotenza);

        // --- 0. l'ho gia' vista? ---
        // Il controllo APPLICATIVO: copre il caso normale (l'utente ha
        // premuto due volte a distanza di secondi) senza disturbare nessuno.
        // Non copre le due richieste arrivate nello stesso millisecondo: per
        // quelle c'e' il vincolo UNIQUE, al passo 3.
        Optional<Booking> gia = repository.findByIdempotencyKey(chiave);
        if (gia.isPresent()) {
            log.info("Idempotency-Key {} gia' vista: restituisco la prenotazione {} ({}) "
                    + "senza chiamare nessuno", chiave, gia.get().getId(), gia.get().getStato());
            return EsitoPrenotazione.ripetuta(gia.get());
        }

        // L'identificativo dell'intera operazione, generato UNA VOLTA e
        // ripetuto identico a ogni servizio coinvolto. Dal G8 non serve piu'
        // solo a ricucire i log: e' la CHIAVE DI IDEMPOTENZA con cui ognuno
        // dei tre partecipanti riconosce un passo gia' eseguito (passo 8.3).
        //
        // Nuovo a ogni TENTATIVO, mentre la chiave qui sopra e' la stessa a
        // ogni ripetizione della stessa INTENZIONE: e' la differenza che
        // rende utili tutti e due.
        String sagaId = UUID.randomUUID().toString();
        log.info("[saga {}] inizio prenotazione: spettacolo {}, {} posti, categoria {}, cliente {}",
                sagaId, showId, quantita, customerType, customerId);

        // --- 1. i dati dello spettacolo ---
        ShowJson spettacolo = showsClient.perId(showId);

        // --- 2. il prezzo unitario ---
        // eveningShow lo decide CHI CONOSCE L'ORARIO, cioe' shows-service:
        // noi lo ritrasmettiamo e non lo ricalcoliamo.
        BigDecimal prezzoUnitario = pricingClient.prezzoUnitario(
                spettacolo.basePrice(), customerType, spettacolo.eveningShow());

        Booking nuova = new Booking(
                chiave, sagaId, showId, customerType, quantita,
                spettacolo.movieTitle(),   // passo 6.3: copiati, non referenziati
                spettacolo.startTime(),
                prezzoUnitario);

        // --- 3. la prenotazione IN_CORSO e la saga AVVIATA ---
        AperturaSaga apertura;
        try {
            apertura = store.apri(nuova, customerId, puntiFedelta(nuova.getTotalPrice()));

        } catch (DataIntegrityViolationException e) {
            // ===============================================================
            // PASSO 7.6 — LA CORSA PERSA, E NON E' UN ERRORE.
            //
            // Ci si arriva solo se due richieste con la STESSA chiave sono
            // passate insieme dal controllo del passo 0. Il vincolo UNIQUE
            // della V2 ne ha fatta passare una; questa e' l'altra.
            //
            // E dal G8 la si perde PRIMA di aver toccato qualsiasi altro
            // servizio: non c'e' niente da compensare, mentre al G7 i posti
            // di questo tentativo restavano scalati. E' il guadagno concreto
            // di aver spostato la INSERT all'inizio.
            // ===============================================================
            Booking vincitrice = repository.findByIdempotencyKey(chiave)
                    // Se non c'e', il vincolo violato era un ALTRO (saga_id):
                    // non e' una corsa, e' un caso che non sappiamo spiegare.
                    // L'eccezione originale risale e diventa un 409/500.
                    .orElseThrow(() -> e);

            log.info("[saga {}] corsa persa sulla Idempotency-Key {}: vince la prenotazione {}. "
                    + "Nessun servizio e' stato chiamato, niente da compensare",
                    sagaId, chiave, vincitrice.getId());

            return EsitoPrenotazione.ripetuta(vincitrice);
        }

        // --- 4. la saga ---
        // Da qui in avanti ogni passo cambia qualcosa in un altro processo, e
        // ogni passo ha la sua compensazione. Se qualcosa va storto,
        // BookingSaga rimette a posto e rilancia: il 402 o il 503 arrivano
        // al chiamante, e il sistema non resta a meta'.
        return EsitoPrenotazione.creata(saga.esegui(apertura.prenotazione(), apertura.saga()));
    }

    /**
     * PASSO 8.2 — QUANTI PUNTI VALE QUESTO ACQUISTO.
     *
     * Un punto per euro speso, arrotondato per DIFETTO. La regola sta qui e
     * non in loyalty-service, ed e' una decisione: i punti dipendono da
     * quanto si e' speso, e chi sa quanto si e' speso e' chi ha appena
     * calcolato il totale. loyalty-service somma e sottrae, e non deve
     * conoscere ne' prezzi ne' listini — il giorno in cui la promozione
     * diventa "punti doppi il mercoledi'", a cambiare e' questa riga.
     *
     * RoundingMode.FLOOR e non HALF_UP: quando si regala qualcosa, si
     * sceglie esplicitamente da che parte arrotondare invece di lasciarlo
     * decidere al default. 9.99 euro fanno 9 punti, e nessuno si stupisce.
     */
    private static int puntiFedelta(BigDecimal totale) {
        return totale.setScale(0, RoundingMode.FLOOR).intValue();
    }

    /**
     * PASSO 7.6 — la chiave arriva da FUORI, quindi si controlla.
     *
     * Header presente ma vuoto, o lungo duecento caratteri: sono richieste
     * sbagliate del client, cioe' dei 400 (l'IllegalArgumentException la
     * traduce GestoreErrori). Senza questo controllo la seconda diventerebbe
     * un errore del database su una VARCHAR(64), cioe' un 500 che racconta
     * un guasto nostro per una richiesta malfatta di qualcun altro.
     */
    private static String validata(String chiaveIdempotenza) {
        if (chiaveIdempotenza == null || chiaveIdempotenza.isBlank()) {
            throw new IllegalArgumentException(
                    "L'header Idempotency-Key e' obbligatorio e non puo' essere vuoto");
        }
        String chiave = chiaveIdempotenza.trim();
        if (chiave.length() > 64) {
            throw new IllegalArgumentException(
                    "L'header Idempotency-Key non puo' superare i 64 caratteri");
        }
        return chiave;
    }

    /**
     * readOnly = true non e' cosmetico: Hibernate salta il dirty checking e
     * il database puo' instradare la query su una replica di lettura.
     */
    @Transactional(readOnly = true)
    public Booking perId(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new BookingNotFoundException(id));
    }

    /**
     * Un elenco senza limiti e' una bomba a orologeria (passo 3.4): funziona
     * con tre righe e affoga con trecentomila.
     *
     * Nota: per rispondere NON si chiama nessuno. Titolo e orario sono sulla
     * nostra riga (passo 6.3), quindi l'elenco delle prenotazioni si legge
     * anche con tutti gli altri servizi spenti. E' il vantaggio concreto
     * della copia — e dal G8, con cinque servizi in giro, vale il quintuplo.
     */
    @Transactional(readOnly = true)
    public Page<Booking> elenco(Pageable pageable) {
        return repository.findAll(pageable);
    }
}
