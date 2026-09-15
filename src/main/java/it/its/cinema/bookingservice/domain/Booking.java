package it.its.cinema.bookingservice.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * PASSO 6.2 / 6.3 — LA PRENOTAZIONE, E I DATI CHE SI PORTA DIETRO.
 *
 * ===========================================================================
 * PASSO 6.3 — movieTitle, startTime E unitPrice NON SONO UNA CACHE.
 *
 * Sono COPIATI da shows-service e da pricing-service al momento
 * dell'acquisto, e da quel momento non si aggiornano MAI piu'. Non e' una
 * dimenticanza da sistemare: e' la regola.
 *
 * Il biglietto dice quanto hai pagato IERI, non quanto costa oggi.
 *
 * Se domani il cinema alza il prezzo a 12.00 euro, o sposta lo spettacolo di
 * mezz'ora, o corregge un refuso nel titolo del film, questa riga non deve
 * cambiare di una virgola: e' il verbale di cio' che e' successo. Una cache
 * si invalida quando la sorgente cambia; un FATTO STORICO no.
 *
 * La differenza pratica: se fossero una cache, servirebbe un meccanismo per
 * accorgersi dei cambiamenti a monte (eventi, polling, scadenze) e tutto il
 * sistema diventerebbe piu' complicato. Essendo fatti, non serve niente: la
 * copia e' il punto, non il compromesso.
 *
 * E' anche cio' che rende leggibile una prenotazione quando shows-service e'
 * spento: per stampare il biglietto non serve chiamare nessuno.
 * ===========================================================================
 *
 * NESSUNA FOREIGN KEY VERSO shows.
 * showId e' un Long, non un @ManyToOne: lo spettacolo vive in un ALTRO
 * database, e i database non si parlano fra loro. E' la rinuncia piu' concreta
 * del passaggio ai microservizi — l'integrita' referenziale fra servizi non
 * esiste piu', e il suo posto lo prende la saga.
 */
@Entity
@Table(name = "bookings")
@Getter
@NoArgsConstructor
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * PASSO 7.6 — LA CHIAVE DI IDEMPOTENZA, CHE ARRIVA DAL CLIENT.
     *
     * Non confonderla con il sagaId qui sotto: sono due identita' diverse.
     *
     *   idempotencyKey  la sceglie il CLIENT, una per INTENZIONE. "Compra
     *                   questi due posti" resta una cosa sola anche se il
     *                   pulsante viene premuto tre volte in ascensore.
     *   sagaId          lo generiamo NOI, uno per TENTATIVO.
     *
     * UNIQUE nel database (V2), e li' sta la protezione vera: il controllo
     * in Java lo superano entrambe le richieste che arrivano insieme, il
     * vincolo ne fa passare una sola.
     */
    @Column(name = "idempotency_key", nullable = false, unique = true, length = 64)
    private String idempotencyKey;

    /**
     * PASSO 6.4 — l'identificativo dell'intera operazione di acquisto.
     *
     * Lo generiamo noi (siamo chi coordina) e lo mandiamo identico a ogni
     * servizio coinvolto. Salvarlo qui serve a due cose: ricucire i log di
     * tre processi diversi, e — dal G8 — riconoscere che una saga e' gia'
     * stata eseguita invece di rieseguirla.
     *
     * UNIQUE nel database, non solo qui: e' il vincolo che al G8 rendera'
     * l'operazione idempotente anche se a ritentare sono due thread insieme.
     */
    @Column(name = "saga_id", nullable = false, unique = true, length = 64)
    private String sagaId;

    /** L'id dello spettacolo NELL'ALTRO servizio. Nessuna FK, vedi sopra. */
    @Column(name = "show_id", nullable = false)
    private Long showId;

    /**
     * EnumType.STRING e MAI EnumType.ORDINAL, che e' il default di JPA.
     *
     * Con ORDINAL il database memorizza 0, 1, 2 — la POSIZIONE del valore
     * nell'enum. Il giorno in cui qualcuno inserisce una categoria in mezzo
     * all'elenco, tutte le righe gia' scritte cambiano significato in
     * silenzio: gli studenti diventano senior. Con STRING nel database c'e'
     * scritto "STUDENT", e riordinare l'enum non rompe niente.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "customer_type", nullable = false, length = 20)
    private CustomerType customerType;

    @Column(nullable = false)
    private int quantity;

    // ---- PASSO 6.3: i fatti copiati al momento dell'acquisto ----

    @Column(name = "movie_title", nullable = false, length = 200)
    private String movieTitle;

    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;

    /** BigDecimal, MAI double: in virgola mobile 0.1 + 0.2 non fa 0.3. */
    @Column(name = "unit_price", nullable = false, precision = 8, scale = 2)
    private BigDecimal unitPrice;

    /**
     * Il totale e' DERIVATO (unitPrice per quantity), e viene salvato lo
     * stesso. E' una denormalizzazione voluta, e vale la pena sapere perche':
     * un totale ricalcolato al volo dipende dal codice di OGGI, e il giorno in
     * cui la formula cambiera' (un arrotondamento diverso, uno sconto sul
     * gruppo) le ricevute gia' emesse cambierebbero di importo. Come per
     * unitPrice: e' un fatto storico, quindi si scrive.
     */
    @Column(name = "total_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalPrice;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /**
     * PASSO 8.4 — LO STATO, CHE PRIMA DEL G8 NON SERVIVA.
     *
     * Fino al G7 una prenotazione o veniva salvata o non esisteva: la INSERT
     * era l'ultimo passo, e arrivarci significava che era andato tutto bene.
     *
     * Dal G8 la riga si scrive PRIMA delle chiamate agli altri servizi — e
     * non e' un capriccio, e' necessario: il pagamento vuole il bookingId, e
     * per averlo la riga deve gia' esistere. Da quel momento esiste un
     * intervallo, lungo quanto tre chiamate di rete, in cui la prenotazione
     * c'e' ma l'acquisto non e' concluso.
     *
     * Quell'intervallo va chiamato per nome, altrimenti chiunque legga la
     * tabella — un elenco, un report, un lavoro periodico — conta come
     * vendute delle prenotazioni che nessuno ha ancora pagato.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StatoPrenotazione stato;

    /**
     * Il costruttore valida, come in Show (passo 4.1): il DTO difende il
     * confine HTTP, questo difende l'oggetto da CHIUNQUE lo costruisca —
     * un test, un importatore, il service del G8. Chi entra da una porta
     * diversa da HTTP non incontra nessun @Valid.
     *
     * Il totale lo calcola qui e non lo riceve: un chiamante che potesse
     * passare un totale potrebbe passarne uno sbagliato.
     */
    public Booking(String idempotencyKey, String sagaId,
                   Long showId, CustomerType customerType, int quantity,
                   String movieTitle, LocalDateTime startTime, BigDecimal unitPrice) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("La chiave di idempotenza e' obbligatoria");
        }
        // Il tetto non e' pignoleria: la colonna e' VARCHAR(64), e senza
        // questo controllo una chiave piu' lunga diventerebbe un errore del
        // database (un 500) invece che una richiesta rifiutata (un 400).
        if (idempotencyKey.length() > 64) {
            throw new IllegalArgumentException(
                    "La chiave di idempotenza non puo' superare i 64 caratteri");
        }
        if (sagaId == null || sagaId.isBlank()) {
            throw new IllegalArgumentException("Il sagaId e' obbligatorio");
        }
        if (showId == null) {
            throw new IllegalArgumentException("Lo spettacolo e' obbligatorio");
        }
        if (customerType == null) {
            throw new IllegalArgumentException("La categoria del cliente e' obbligatoria");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("La quantita' deve essere positiva");
        }
        if (movieTitle == null || movieTitle.isBlank()) {
            throw new IllegalArgumentException("Il titolo del film e' obbligatorio");
        }
        if (startTime == null) {
            throw new IllegalArgumentException("L'orario di inizio e' obbligatorio");
        }
        if (unitPrice == null || unitPrice.signum() < 0) {
            throw new IllegalArgumentException("Il prezzo unitario non puo' essere negativo");
        }

        this.idempotencyKey = idempotencyKey;
        this.sagaId = sagaId;
        this.showId = showId;
        this.customerType = customerType;
        this.quantity = quantity;
        this.movieTitle = movieTitle;
        this.startTime = startTime;
        this.unitPrice = unitPrice;
        this.totalPrice = unitPrice.multiply(BigDecimal.valueOf(quantity));
        this.createdAt = LocalDateTime.now();

        // Nasce IN_CORSO, e non CONFERMATA: al G8 la riga viene scritta
        // prima che i posti siano scalati e prima che il pagamento sia
        // passato. Il costruttore non puo' promettere un acquisto che non
        // e' ancora avvenuto.
        this.stato = StatoPrenotazione.IN_CORSO;
    }

    /**
     * PASSO 8.5 — tutti i passi sono riusciti: da qui e' un biglietto.
     */
    public void conferma() {
        this.stato = StatoPrenotazione.CONFERMATA;
    }

    /**
     * PASSO 8.5 — la saga e' fallita e ha compensato.
     *
     * La riga NON si cancella. Cancellarla sarebbe piu' pulito da guardare e
     * toglierebbe l'unica risposta alla domanda "ho provato a comprare e non
     * ha funzionato, cosa e' successo?" — che e' la domanda che il cliente fa
     * il giorno dopo, quando di quel tentativo non resta piu' nient'altro.
     */
    public void fallisci() {
        this.stato = StatoPrenotazione.FALLITA;
    }

    public boolean confermata() {
        return stato == StatoPrenotazione.CONFERMATA;
    }
}
