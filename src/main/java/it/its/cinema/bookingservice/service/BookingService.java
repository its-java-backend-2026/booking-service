package it.its.cinema.bookingservice.service;

import java.math.BigDecimal;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PASSO 6.10 — IL FLUSSO DI POST /bookings, CHE ATTRAVERSA TRE PROCESSI.
 *
 *     1. GET  shows-service   /shows/{id}       prezzo base, orario, titolo
 *     2. POST pricing-service /prices/quote     prezzo unitario
 *     3. POST shows-service   /shows/{id}/reserve
 *     4. salva su booking_db                    -> 201 Created
 *
 * ===========================================================================
 * GUARDATE IL PASSO 3, ED E' IL PUNTO PIU' IMPORTANTE DI TUTTA LA GIORNATA.
 *
 * Se il passo 4 fallisce — il database e' pieno, la connessione cade, il
 * processo viene ucciso fra il 3 e il 4 — I POSTI RESTANO RISERVATI. Sono
 * stati scalati in un altro servizio, dentro una transazione che si e' gia'
 * chiusa con successo, e la nostra non ha nessun potere su quella.
 *
 * Il cliente riceve un errore e non ha nessuna prenotazione; il cinema ha
 * due poltrone in meno da vendere, per sempre, e nessuno lo sa.
 *
 * NON SI RISOLVE CON UNA TRANSAZIONE PIU' GRANDE. La tentazione e' mettere
 * @Transactional su questo metodo e sperare: non funziona, perche' una
 * transazione locale non puo' annullare un POST HTTP gia' arrivato a
 * destinazione. Non esiste il rollback distribuito, o meglio esiste (XA,
 * two-phase commit) e nei microservizi non si usa, perche' tiene bloccate le
 * risorse di tutti i partecipanti per tutta la durata dell'operazione.
 *
 * La soluzione e' la SAGA: ogni passo ha una compensazione, e chi coordina
 * la esegue quando un passo successivo fallisce. ShowsClient.rilascia() e'
 * gia' scritto e oggi non lo chiama nessuno, di proposito. E' il G8.
 *
 * Oggi ci si ferma qui, con il buco in vista e il commento che lo dice:
 * un problema che si e' visto succedere si risolve meglio di uno raccontato.
 * ===========================================================================
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BookingService {

    private final BookingRepository repository;
    private final ShowsClient showsClient;
    private final PricingClient pricingClient;

    /**
     * QUI NON C'E' @Transactional, ED E' UNA SCELTA.
     *
     * Metterla significherebbe tenere aperta una transazione sul database —
     * e quindi una connessione del pool occupata — per tutta la durata di
     * TRE chiamate HTTP. Con i timeout del passo 6.6 sono fino a 9 secondi
     * per prenotazione: bastano una decina di richieste lente insieme per
     * esaurire il pool, e a quel punto anche le GET smettono di rispondere.
     *
     * E' la stessa lezione di open-in-view: false del G2, vista da un'altra
     * angolazione. Una transazione si apre il piu' tardi possibile e si
     * chiude il prima possibile, e MAI intorno a un'attesa di rete.
     *
     * Qui non serve: fino al passo 4 non si tocca il database, e il passo 4
     * e' una singola INSERT, che la sua transazione ce l'ha da sola.
     */
    public Booking crea(Long showId, CustomerType customerType, int quantita) {

        // L'identificativo dell'intera operazione, generato UNA VOLTA e
        // ripetuto identico a ogni servizio coinvolto. E' cio' che permette
        // di ricucire i log di tre processi diversi, e dal G8 e' la chiave
        // dell'idempotenza.
        String sagaId = UUID.randomUUID().toString();
        log.info("[saga {}] inizio prenotazione: spettacolo {}, {} posti, categoria {}",
                sagaId, showId, quantita, customerType);

        // --- 1. i dati dello spettacolo ---
        // Serve il prezzo base, l'orario e il titolo. E' anche il momento in
        // cui si scopre che lo spettacolo non esiste: il 404 di shows diventa
        // un 404 nostro (passo 6.9) invece di una riga orfana sul database.
        ShowJson spettacolo = showsClient.perId(showId);

        // --- 2. il prezzo unitario ---
        // eveningShow lo decide CHI CONOSCE L'ORARIO, cioe' shows-service:
        // noi lo ritrasmettiamo e non lo ricalcoliamo. Ricalcolarlo qui
        // vorrebbe dire duplicare la regola "dalle 20:00 in poi" in due
        // servizi, e il giorno in cui cambiasse ne cambierebbe una sola.
        BigDecimal prezzoUnitario = pricingClient.prezzoUnitario(
                spettacolo.basePrice(), customerType, spettacolo.eveningShow());

        // --- 3. la riserva dei posti ---
        // E' il primo passo IRREVERSIBILE: da qui in poi qualcosa e' cambiato
        // in un altro servizio. Un 409 qui e' del tutto normale — fra il
        // passo 1 e questo c'e' una finestra in cui chiunque puo' comprare gli
        // ultimi posti — e diventa un 409 anche per il nostro chiamante.
        showsClient.riserva(showId, quantita, sagaId);

        // --- 4. il fatto storico ---
        // Da qui in avanti la prenotazione esiste. Se questa riga fallisce,
        // i posti del passo 3 restano riservati: vedi il commento in cima.
        Booking prenotazione = repository.save(new Booking(
                sagaId,
                showId,
                customerType,
                quantita,
                spettacolo.movieTitle(),   // passo 6.3: copiati, non referenziati
                spettacolo.startTime(),
                prezzoUnitario));

        log.info("[saga {}] prenotazione {} creata: {} x {} = {}",
                sagaId, prenotazione.getId(), quantita, prezzoUnitario,
                prenotazione.getTotalPrice());
        return prenotazione;
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
     * anche con shows-service spento. E' il vantaggio concreto della copia.
     */
    @Transactional(readOnly = true)
    public Page<Booking> elenco(Pageable pageable) {
        return repository.findAll(pageable);
    }
}
