package it.its.cinema.bookingservice;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import it.its.cinema.bookingservice.client.LoyaltyClient;
import it.its.cinema.bookingservice.client.PaymentClient;
import it.its.cinema.bookingservice.client.PricingClient;
import it.its.cinema.bookingservice.client.ShowsClient;
import it.its.cinema.bookingservice.client.dto.ShowJson;
import it.its.cinema.bookingservice.domain.Booking;
import it.its.cinema.bookingservice.domain.CustomerType;
import it.its.cinema.bookingservice.domain.PagamentoRifiutatoException;
import it.its.cinema.bookingservice.domain.PassoSaga;
import it.its.cinema.bookingservice.domain.SagaState;
import it.its.cinema.bookingservice.domain.ServizioNonDisponibileException;
import it.its.cinema.bookingservice.domain.StatoPrenotazione;
import it.its.cinema.bookingservice.domain.StatoSaga;
import it.its.cinema.bookingservice.repository.BookingRepository;
import it.its.cinema.bookingservice.repository.SagaStateRepository;
import it.its.cinema.bookingservice.service.BookingService;
import it.its.cinema.bookingservice.service.EsitoPrenotazione;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PASSI 8.4 e 8.8 — LA SAGA CON UN DATABASE VERO.
 *
 * ===========================================================================
 * COSA AGGIUNGE QUESTO TEST A BookingSagaTest, CHE PROVA LE STESSE COSE.
 *
 * BookingSagaTest verifica le DECISIONI: quali compensazioni, in quale
 * ordine. Lo fa con i mock, in un millisecondo, e non tocca niente.
 *
 * Qui invece si guarda cosa RESTA SCRITTO quando tutto e' finito, ed e' la
 * meta' che i mock non possono mostrare:
 *
 *   - la prenotazione e' CONFERMATA o FALLITA?
 *   - la saga e' COMPLETATA, COMPENSATA o COMPENSAZIONE_PARZIALE?
 *   - a che passo si e' fermata?
 *   - le transazioni di SagaStore hanno scritto davvero, o sono rimaste
 *     senza proxy (passo 8.6)?
 *
 * L'ultimo punto e' quello che giustifica il container: una @Transactional
 * che non si apre non da' nessun errore, e un test con i mock non se ne
 * accorgerebbe mai.
 *
 * I quattro client sono @MockitoBean: qui non si sta provando la rete — quello
 * e' il G10 — si sta provando cio' che succede su booking_db al variare di
 * come rispondono gli altri.
 * ===========================================================================
 */
@SpringBootTest
@Testcontainers
@DisplayName("La saga del G8 — cosa resta scritto su booking_db")
class SagaIT {

    private static final String CLIENTE = "mario.rossi";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    BookingService service;

    @Autowired
    BookingRepository prenotazioni;

    @Autowired
    SagaStateRepository saghe;

    @MockitoBean
    ShowsClient showsClient;

    @MockitoBean
    PricingClient pricingClient;

    @MockitoBean
    PaymentClient paymentClient;

    @MockitoBean
    LoyaltyClient loyaltyClient;

    @BeforeEach
    void serviziSani() {
        when(showsClient.perId(1L)).thenReturn(new ShowJson(1L, "Dune - Parte Due",
                LocalDateTime.of(2027, 1, 15, 21, 0), new BigDecimal("10.00"), true));
        when(pricingClient.prezzoUnitario(any(), any(), eq(true)))
                .thenReturn(new BigDecimal("10.00"));
        when(loyaltyClient.storna(anyString(), anyString(), anyInt())).thenReturn(true);
    }

    private EsitoPrenotazione prenota(String chiave, int posti) {
        return service.crea(chiave, 1L, CLIENTE, CustomerType.STUDENT, posti);
    }

    private SagaState sagaDi(Booking prenotazione) {
        return saghe.findByBookingId(prenotazione.getId()).orElseThrow();
    }

    @Test
    @DisplayName("Percorso felice: prenotazione CONFERMATA e saga COMPLETATA")
    void percorsoFelice() {
        Booking prenotazione = prenota("chiave-felice", 2).prenotazione();

        Booking riletta = prenotazioni.findById(prenotazione.getId()).orElseThrow();
        assertThat(riletta.getStato()).isEqualTo(StatoPrenotazione.CONFERMATA);

        SagaState saga = sagaDi(prenotazione);
        assertThat(saga.getStato()).isEqualTo(StatoSaga.COMPLETATA);
        assertThat(saga.getPassoRaggiunto()).isEqualTo(PassoSaga.PUNTI_ACCREDITATI);
        assertThat(saga.getUltimoErrore()).isNull();
    }

    @Test
    @DisplayName("PASSO 8.4 — la saga porta i parametri con cui si compenserebbe")
    void laSagaPortaISuoiParametri() {
        Booking prenotazione = prenota("chiave-parametri", 3).prenotazione();

        SagaState saga = sagaDi(prenotazione);

        // Sono i dati che servirebbero a un lavoro periodico per riprendere
        // questa saga senza dover ricostruire niente: chi, cosa, quanto.
        assertThat(saga.getCustomerId()).isEqualTo(CLIENTE);
        assertThat(saga.getShowId()).isEqualTo(1L);
        assertThat(saga.getQuantity()).isEqualTo(3);
        assertThat(saga.getLoyaltyPoints()).isEqualTo(30);   // 3 x 10.00
        assertThat(saga.getBookingId()).isEqualTo(prenotazione.getId());
    }

    /**
     * ===================================================================
     * CONSEGNA G8 — UN PAGAMENTO RIFIUTATO NON LASCIA MAI POSTI BLOCCATI.
     *
     * E' il test che si mostra in aula. Provoca il fallimento del passo 8.8
     * — il pagamento dice di no — e verifica le tre cose che devono essere
     * vere subito dopo:
     *
     *   1. i posti sono stati rilasciati
     *   2. la prenotazione risulta FALLITA, e non e' sparita
     *   3. saga_state racconta dove si era fermata e perche'
     * ===================================================================
     */
    @Test
    @DisplayName("PASSO 8.8 — pagamento rifiutato: posti rilasciati, saga COMPENSATA")
    void pagamentoRifiutato() {
        doThrow(new PagamentoRifiutatoException("saga-x",
                "Importo 200.00 oltre la soglia autorizzabile di 100.00"))
                .when(paymentClient).autorizza(anyString(), any(), any());

        assertThatThrownBy(() -> prenota("chiave-rifiutata", 20))
                .isInstanceOf(PagamentoRifiutatoException.class);

        // 1. i posti sono tornati disponibili
        verify(showsClient).rilascia(eq(1L), eq(20), anyString());

        // 2. la prenotazione c'e' ancora, e dice com'e' andata
        Booking prenotazione = prenotazioni.findByIdempotencyKey("chiave-rifiutata")
                .orElseThrow();
        assertThat(prenotazione.getStato()).isEqualTo(StatoPrenotazione.FALLITA);

        // 3. la saga racconta dove si e' fermata e perche'
        SagaState saga = sagaDi(prenotazione);
        assertThat(saga.getStato()).isEqualTo(StatoSaga.COMPENSATA);
        assertThat(saga.getPassoRaggiunto()).isEqualTo(PassoSaga.POSTI_RISERVATI);
        assertThat(saga.getUltimoErrore()).contains("oltre la soglia");
    }

    @Test
    @DisplayName("PASSO 8.2 — se lo storno dei punti e' parziale, la saga lo dice")
    void compensazioneParziale() {
        // Tutto riesce, poi loyalty non riesce a stornare tutti i punti
        // perche' il cliente li aveva gia' spesi. Nessuna eccezione: solo un
        // false che arriva fino a qui.
        when(loyaltyClient.storna(anyString(), anyString(), anyInt())).thenReturn(false);
        doThrow(new ServizioNonDisponibileException("shows-service", "timeout sul rilascio"))
                .when(showsClient).rilascia(anyLong(), anyInt(), anyString());
        doThrow(new PagamentoRifiutatoException("saga-y", "oltre la soglia"))
                .when(paymentClient).autorizza(anyString(), any(), any());

        assertThatThrownBy(() -> prenota("chiave-parziale", 2))
                .isInstanceOf(PagamentoRifiutatoException.class);

        Booking prenotazione = prenotazioni.findByIdempotencyKey("chiave-parziale")
                .orElseThrow();
        SagaState saga = sagaDi(prenotazione);

        // Il rilascio dei posti e' fallito: la saga NON puo' dirsi compensata.
        assertThat(saga.getStato()).isEqualTo(StatoSaga.COMPENSAZIONE_PARZIALE);

        // ===================================================================
        // ED E' LA RIGA CHE UN GIORNO QUALCUNO CERCHERA':
        //
        //     SELECT * FROM saga_state WHERE stato = 'COMPENSAZIONE_PARZIALE';
        //
        // Senza questa tabella, quei due posti resterebbero bloccati e
        // nessuno saprebbe nemmeno che esistono.
        // ===================================================================
        assertThat(saghe.findAll())
                .filteredOn(s -> s.getStato() == StatoSaga.COMPENSAZIONE_PARZIALE)
                .isNotEmpty();
    }

    @Test
    @DisplayName("PASSO 7.6 — la stessa chiave due volte lascia UNA prenotazione e UNA saga")
    void laStessaChiaveNonRaddoppiaNiente() {
        Booking prima = prenota("chiave-ripetuta", 2).prenotazione();
        EsitoPrenotazione seconda = prenota("chiave-ripetuta", 2);

        assertThat(seconda.giaEsistente()).isTrue();
        assertThat(seconda.prenotazione().getId()).isEqualTo(prima.getId());

        assertThat(prenotazioni.findAll())
                .filteredOn(b -> "chiave-ripetuta".equals(b.getIdempotencyKey()))
                .hasSize(1);
        assertThat(saghe.findAll())
                .filteredOn(s -> s.getBookingId().equals(prima.getId()))
                .hasSize(1);
    }

    @Test
    @DisplayName("PASSO 8.6 — le scritture di SagaStore sono davvero passate dal database")
    void leScrittureSonoPassate() {
        // ===================================================================
        // IL TEST CHE PROVA LA REGOLA DEL PASSO 8.6.
        //
        // Se i metodi di SagaStore fossero metodi privati dell'orchestratore,
        // la loro @Transactional non aprirebbe niente e il passo raggiunto
        // non arriverebbe mai sul database. Non ci sarebbe nessun errore:
        // semplicemente, rileggendo, si troverebbe ancora AVVIATA.
        //
        // Si rilegge DAL REPOSITORY, non dall'oggetto che gira in memoria:
        // e' l'unico modo di distinguere le due cose.
        // ===================================================================
        Booking prenotazione = prenota("chiave-scritture", 1).prenotazione();

        SagaState riletta = sagaDi(prenotazione);
        assertThat(riletta.getPassoRaggiunto()).isEqualTo(PassoSaga.PUNTI_ACCREDITATI);
        assertThat(riletta.getAggiornataIl()).isNotNull();
    }
}
