package it.its.cinema.bookingservice;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import it.its.cinema.bookingservice.client.PricingClient;
import it.its.cinema.bookingservice.client.ShowsClient;
import it.its.cinema.bookingservice.client.dto.ShowJson;
import it.its.cinema.bookingservice.domain.Booking;
import it.its.cinema.bookingservice.domain.CustomerType;
import it.its.cinema.bookingservice.domain.SagaState;
import it.its.cinema.bookingservice.domain.ServizioNonDisponibileException;
import it.its.cinema.bookingservice.domain.SpettacoloNonTrovatoException;
import it.its.cinema.bookingservice.repository.BookingRepository;
import it.its.cinema.bookingservice.service.AperturaSaga;
import it.its.cinema.bookingservice.service.BookingSaga;
import it.its.cinema.bookingservice.service.BookingService;
import it.its.cinema.bookingservice.service.EsitoPrenotazione;
import it.its.cinema.bookingservice.service.SagaStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * PASSI 7.6 e 8.5 — CIO' CHE SUCCEDE PRIMA CHE LA SAGA COMINCI.
 *
 * Dal G8 questa classe ha un compito piu' piccolo e piu' chiaro di prima:
 * riconoscere una richiesta gia' vista, raccogliere i dati (spettacolo e
 * prezzo), aprire la prenotazione, e passare la mano a BookingSaga. I tre
 * passi distribuiti — posti, pagamento, punti — e le loro compensazioni sono
 * in BookingSagaTest, che e' il posto in cui si legge il G8.
 *
 * La divisione dei test segue quella del codice, ed e' il modo piu' rapido di
 * accorgersi se la seconda si e' guastata.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BookingService — l'idempotenza (7.6) e l'apertura della saga (8.4)")
class BookingServiceTest {

    @Mock
    BookingRepository repository;

    @Mock
    ShowsClient showsClient;

    @Mock
    PricingClient pricingClient;

    @Mock
    SagaStore store;

    @Mock
    BookingSaga saga;

    @InjectMocks
    BookingService service;

    /** Uno spettacolo serale da 10.00: l'esempio del passo 6.1. */
    private ShowJson spettacolo;

    private static final String CHIAVE = "chiave-del-client-1";
    private static final String CLIENTE = "mario.rossi";

    @BeforeEach
    void setUp() {
        spettacolo = new ShowJson(1L, "Dune - Parte Due",
                LocalDateTime.of(2027, 1, 15, 21, 0), new BigDecimal("10.00"), true);
    }

    private void chiaveMaiVista() {
        when(repository.findByIdempotencyKey(CHIAVE)).thenReturn(Optional.empty());
    }

    private Booking prenotazioneGiaEsistente() {
        return new Booking(CHIAVE, "saga-di-ieri", 1L, CustomerType.STUDENT, 2,
                "Dune - Parte Due", LocalDateTime.of(2027, 1, 15, 21, 0),
                new BigDecimal("10.00"));
    }

    /** L'apertura riuscita: la prenotazione salvata e la sua saga AVVIATA. */
    private AperturaSaga apertura(Booking prenotazione) {
        return new AperturaSaga(prenotazione,
                new SagaState(1L, CLIENTE, 1L, prenotazione.getQuantity(), 20));
    }

    private void tuttoVaBene() {
        chiaveMaiVista();
        when(showsClient.perId(1L)).thenReturn(spettacolo);
        when(pricingClient.prezzoUnitario(any(), any(), eq(true)))
                .thenReturn(new BigDecimal("10.00"));
        when(store.apri(any(), anyString(), anyInt()))
                .thenAnswer(i -> apertura(i.getArgument(0)));
        when(saga.esegui(any(), any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    @DisplayName("L'ordine e' quello del passo 8.5: dati, prezzo, riga, e poi la saga")
    void iPassiInOrdine() {
        tuttoVaBene();

        service.crea(CHIAVE, 1L, CLIENTE, CustomerType.STUDENT, 2);

        InOrder ordine = inOrder(repository, showsClient, pricingClient, store, saga);
        ordine.verify(repository).findByIdempotencyKey(CHIAVE);
        ordine.verify(showsClient).perId(1L);
        ordine.verify(pricingClient).prezzoUnitario(new BigDecimal("10.00"),
                CustomerType.STUDENT, true);
        ordine.verify(store).apri(any(), eq(CLIENTE), anyInt());
        ordine.verify(saga).esegui(any(), any());
    }

    @Test
    @DisplayName("PASSO 8.5 — la riga si scrive PRIMA di riservare i posti")
    void laRigaSiScrivePrimaDiToccareGliAltri() {
        tuttoVaBene();

        service.crea(CHIAVE, 1L, CLIENTE, CustomerType.STUDENT, 2);

        // ===================================================================
        // E' il buco del G6 chiuso, e si vede da qui: al momento in cui
        // BookingService restituisce, i posti non sono ancora stati
        // riservati da NESSUNO — ci pensera' la saga, che ha gia' una riga
        // e uno stato da cui sa come rimetterli a posto.
        //
        // Fino al G7 l'ordine era rovesciato: prima si riservava, poi si
        // salvava, e una INSERT fallita lasciava i posti scalati per sempre.
        // ===================================================================
        verify(showsClient, never()).riserva(any(), anyInt(), anyString());
        verify(store).apri(any(), eq(CLIENTE), anyInt());
    }

    @Test
    @DisplayName("PASSO 8.2 — i punti sono il totale arrotondato per difetto")
    void iPuntiSonoIlTotaleArrotondatoPerDifetto() {
        chiaveMaiVista();
        when(showsClient.perId(1L)).thenReturn(spettacolo);
        // 3 posti a 9.99 = 29.97 -> 29 punti, non 30.
        when(pricingClient.prezzoUnitario(any(), any(), eq(true)))
                .thenReturn(new BigDecimal("9.99"));
        when(store.apri(any(), anyString(), anyInt()))
                .thenAnswer(i -> apertura(i.getArgument(0)));
        when(saga.esegui(any(), any())).thenAnswer(i -> i.getArgument(0));

        service.crea(CHIAVE, 1L, CLIENTE, CustomerType.STUDENT, 3);

        ArgumentCaptor<Integer> punti = ArgumentCaptor.forClass(Integer.class);
        verify(store).apri(any(), eq(CLIENTE), punti.capture());
        assertThat(punti.getValue()).isEqualTo(29);
    }

    @Test
    @DisplayName("Titolo, orario e prezzo unitario finiscono sulla prenotazione (passo 6.3)")
    void copiaIDatiAlMomentoDellAcquisto() {
        tuttoVaBene();

        Booking creata = service.crea(CHIAVE, 1L, CLIENTE, CustomerType.STUDENT, 2)
                .prenotazione();

        assertThat(creata.getMovieTitle()).isEqualTo("Dune - Parte Due");
        assertThat(creata.getStartTime()).isEqualTo(LocalDateTime.of(2027, 1, 15, 21, 0));
        assertThat(creata.getUnitPrice()).isEqualByComparingTo("10.00");
        assertThat(creata.getTotalPrice()).isEqualByComparingTo("20.00");
    }

    @Test
    @DisplayName("Il sagaId e' generato qui, ed e' diverso a ogni acquisto")
    void sagaIdUnico() {
        tuttoVaBene();

        String primo = service.crea(CHIAVE, 1L, CLIENTE, CustomerType.STUDENT, 2)
                .prenotazione().getSagaId();
        String secondo = service.crea(CHIAVE, 1L, CLIENTE, CustomerType.STUDENT, 2)
                .prenotazione().getSagaId();

        assertThat(primo).isNotBlank().isNotEqualTo(secondo);
    }

    @Test
    @DisplayName("Se lo spettacolo non esiste ci si ferma subito: nessuna riga, nessuna saga")
    void spettacoloInesistenteFermaTutto() {
        chiaveMaiVista();
        when(showsClient.perId(99L)).thenThrow(new SpettacoloNonTrovatoException(99L));

        assertThatThrownBy(() -> service.crea(CHIAVE, 99L, CLIENTE, CustomerType.STUDENT, 2))
                .isInstanceOf(SpettacoloNonTrovatoException.class);

        verifyNoInteractions(pricingClient, store, saga);
    }

    @Test
    @DisplayName("Se pricing non risponde non si apre niente: il fallimento e' pulito")
    void pricingGiuNonApreNiente() {
        chiaveMaiVista();
        when(showsClient.perId(1L)).thenReturn(spettacolo);
        when(pricingClient.prezzoUnitario(any(), any(), eq(true)))
                .thenThrow(new ServizioNonDisponibileException("pricing-service", "timeout"));

        assertThatThrownBy(() -> service.crea(CHIAVE, 1L, CLIENTE, CustomerType.STUDENT, 2))
                .isInstanceOf(ServizioNonDisponibileException.class);

        // Niente riga, niente saga, niente da compensare: e' il caso piu'
        // facile di tutti, e vale la pena avere un test che dice che resta
        // facile.
        verifyNoInteractions(store, saga);
    }

    @Test
    @DisplayName("Una chiave gia' vista restituisce la prenotazione di allora senza chiamare nessuno")
    void chiaveGiaVistaNonChiamaNessuno() {
        Booking diIeri = prenotazioneGiaEsistente();
        when(repository.findByIdempotencyKey(CHIAVE)).thenReturn(Optional.of(diIeri));

        EsitoPrenotazione esito = service.crea(CHIAVE, 1L, CLIENTE, CustomerType.STUDENT, 2);

        assertThat(esito.giaEsistente()).isTrue();
        assertThat(esito.prenotazione()).isSameAs(diIeri);
        verifyNoInteractions(showsClient, pricingClient, store, saga);
    }

    @Test
    @DisplayName("PASSO 8.5 — la corsa persa non tocca nessun altro servizio")
    void corsaPersaNonToccaNessuno() {
        chiaveMaiVista();
        when(showsClient.perId(1L)).thenReturn(spettacolo);
        when(pricingClient.prezzoUnitario(any(), any(), eq(true)))
                .thenReturn(new BigDecimal("10.00"));
        when(store.apri(any(), anyString(), anyInt()))
                .thenThrow(new DataIntegrityViolationException("uk_bookings_idempotency_key"));

        Booking vincitrice = prenotazioneGiaEsistente();
        when(repository.findByIdempotencyKey(CHIAVE))
                .thenReturn(Optional.empty(), Optional.of(vincitrice));

        EsitoPrenotazione esito = service.crea(CHIAVE, 1L, CLIENTE, CustomerType.STUDENT, 2);

        assertThat(esito.giaEsistente()).isTrue();
        assertThat(esito.prenotazione()).isSameAs(vincitrice);

        // ===================================================================
        // IL GUADAGNO DEL G8, IN UNA RIGA.
        //
        // Al G7 la corsa si perdeva DOPO aver riservato i posti, e il
        // commento di allora diceva: "i posti riservati da questo tentativo
        // restano scalati (compensazione al G8)". Adesso la si perde prima
        // di aver chiamato chiunque: non c'e' niente da compensare perche'
        // non c'e' niente da disfare.
        // ===================================================================
        verifyNoInteractions(saga);
        verify(showsClient, never()).riserva(any(), anyInt(), anyString());
    }

    @Test
    @DisplayName("Una violazione di vincolo che NON e' la chiave risale intatta")
    void violazioneDiAltroVincoloRisale() {
        chiaveMaiVista();
        when(showsClient.perId(1L)).thenReturn(spettacolo);
        when(pricingClient.prezzoUnitario(any(), any(), eq(true)))
                .thenReturn(new BigDecimal("10.00"));
        when(store.apri(any(), anyString(), anyInt()))
                .thenThrow(new DataIntegrityViolationException("uk_bookings_saga_id"));
        // La rilettura non trova niente: non era una corsa sulla chiave.
        when(repository.findByIdempotencyKey(CHIAVE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.crea(CHIAVE, 1L, CLIENTE, CustomerType.STUDENT, 2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("La chiave del client finisce sulla prenotazione salvata")
    void laChiaveFinisceSullaRiga() {
        tuttoVaBene();

        service.crea(CHIAVE, 1L, CLIENTE, CustomerType.STUDENT, 2);

        ArgumentCaptor<Booking> salvata = ArgumentCaptor.forClass(Booking.class);
        verify(store).apri(salvata.capture(), eq(CLIENTE), anyInt());
        assertThat(salvata.getValue().getIdempotencyKey()).isEqualTo(CHIAVE);
    }

    @Test
    @DisplayName("Una chiave vuota o troppo lunga e' rifiutata prima di chiamare chiunque")
    void chiaveNonValidaFermaTutto() {
        assertThatThrownBy(() -> service.crea("  ", 1L, CLIENTE, CustomerType.STUDENT, 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.crea("x".repeat(65), 1L, CLIENTE,
                CustomerType.STUDENT, 2))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(repository, showsClient, pricingClient, store, saga);
    }
}
