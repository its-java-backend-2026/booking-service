package it.its.cinema.bookingservice;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import it.its.cinema.bookingservice.client.PricingClient;
import it.its.cinema.bookingservice.client.ShowsClient;
import it.its.cinema.bookingservice.client.dto.ShowJson;
import it.its.cinema.bookingservice.domain.Booking;
import it.its.cinema.bookingservice.domain.CustomerType;
import it.its.cinema.bookingservice.domain.PostiEsauritiException;
import it.its.cinema.bookingservice.domain.ServizioNonDisponibileException;
import it.its.cinema.bookingservice.domain.SpettacoloNonTrovatoException;
import it.its.cinema.bookingservice.repository.BookingRepository;
import it.its.cinema.bookingservice.service.BookingService;
import it.its.cinema.bookingservice.service.EsitoPrenotazione;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * PASSO 6.10 — IL TEST DELL'ORCHESTRAZIONE, SENZA RETE E SENZA DATABASE.
 *
 * Qui i due gateway sono mock: non si sta verificando che le chiamate HTTP
 * funzionino (quello e' il G10, con WireMock), si sta verificando la
 * COREOGRAFIA — quali passi, in quale ordine, e cosa succede quando uno
 * fallisce. E' la logica che il G8 dovra' modificare, quindi e' quella che
 * conviene bloccare adesso con dei test.
 *
 * Nessun contesto di Spring: @ExtendWith(MockitoExtension.class) e basta.
 * Gira in millisecondi.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BookingService — la saga del passo 6.10 e l'idempotenza del 7.6")
class BookingServiceTest {

    @Mock
    BookingRepository repository;

    @Mock
    ShowsClient showsClient;

    @Mock
    PricingClient pricingClient;

    @InjectMocks
    BookingService service;

    /** Uno spettacolo serale da 10.00: l'esempio del passo 6.1. */
    private ShowJson spettacolo;

    /** PASSO 7.6 — la chiave la sceglie il client, qui la scegliamo noi per lui. */
    private static final String CHIAVE = "chiave-del-client-1";

    @BeforeEach
    void setUp() {
        spettacolo = new ShowJson(1L, "Dune - Parte Due",
                LocalDateTime.of(2027, 1, 15, 21, 0), new BigDecimal("10.00"), true);
    }

    /**
     * Dal passo 7.6 ogni prenotazione comincia con "questa chiave l'ho gia'
     * vista?". Rispondere "no" e' il caso normale, e ripeterlo in ogni test
     * non aggiungerebbe niente a nessuno di loro.
     */
    private void chiaveMaiVista() {
        when(repository.findByIdempotencyKey(CHIAVE)).thenReturn(Optional.empty());
    }

    private Booking prenotazioneGiaEsistente() {
        return new Booking(CHIAVE, "saga-di-ieri", 1L, CustomerType.STUDENT, 2,
                "Dune - Parte Due", LocalDateTime.of(2027, 1, 15, 21, 0),
                new BigDecimal("10.00"));
    }

    @Test
    @DisplayName("I quattro passi avvengono nell'ordine del passo 6.10")
    void iQuattroPassiInOrdine() {
        chiaveMaiVista();
        when(showsClient.perId(1L)).thenReturn(spettacolo);
        when(pricingClient.prezzoUnitario(any(), any(), anyBoolean()))
                .thenReturn(new BigDecimal("10.00"));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.crea(CHIAVE, 1L, CustomerType.STUDENT, 2);

        // L'ORDINE E' LA REGOLA, non un dettaglio: riservare PRIMA di sapere
        // il prezzo significherebbe tenere occupati dei posti per un acquisto
        // che potrebbe non concludersi mai.
        InOrder ordine = inOrder(showsClient, pricingClient, repository);
        ordine.verify(showsClient).perId(1L);
        ordine.verify(pricingClient).prezzoUnitario(
                eq(new BigDecimal("10.00")), eq(CustomerType.STUDENT), eq(true));
        ordine.verify(showsClient).riserva(eq(1L), eq(2), anyString());
        ordine.verify(repository).save(any(Booking.class));
    }

    /**
     * PASSO 6.3 — la verifica che i dati vengano COPIATI.
     *
     * Se un giorno qualcuno sostituisse movieTitle con una lettura al volo da
     * shows-service, questo test continuerebbe a passare — ma l'IT e la
     * lettura di GET /bookings/{id} a servizio spento no. Qui si blocca la
     * meta' verificabile: cio' che finisce sulla riga e' cio' che diceva lo
     * spettacolo AL MOMENTO dell'acquisto.
     */
    @Test
    @DisplayName("Titolo, orario e prezzo unitario finiscono sulla prenotazione (passo 6.3)")
    void copiaIDatiAlMomentoDellAcquisto() {
        chiaveMaiVista();
        when(showsClient.perId(1L)).thenReturn(spettacolo);
        when(pricingClient.prezzoUnitario(any(), any(), anyBoolean()))
                .thenReturn(new BigDecimal("10.00"));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));

        Booking creata = service.crea(CHIAVE, 1L, CustomerType.STUDENT, 2).prenotazione();

        assertThat(creata.getMovieTitle()).isEqualTo("Dune - Parte Due");
        assertThat(creata.getStartTime()).isEqualTo(LocalDateTime.of(2027, 1, 15, 21, 0));
        assertThat(creata.getUnitPrice()).isEqualByComparingTo("10.00");
        // il totale e' derivato dal dominio, non ricevuto da nessuno
        assertThat(creata.getTotalPrice()).isEqualByComparingTo("20.00");
        assertThat(creata.getShowId()).isEqualTo(1L);
        assertThat(creata.getCustomerType()).isEqualTo(CustomerType.STUDENT);
    }

    /**
     * Il sagaId e' generato da noi, e' lo STESSO in tutti i passi, ed e'
     * DIVERSO fra due prenotazioni. Tre proprieta', un test.
     */
    @Test
    @DisplayName("Il sagaId e' generato qui, uguale per tutti i passi e diverso a ogni acquisto")
    void sagaIdUnicoECoerente() {
        when(repository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(showsClient.perId(1L)).thenReturn(spettacolo);
        when(pricingClient.prezzoUnitario(any(), any(), anyBoolean()))
                .thenReturn(new BigDecimal("10.00"));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));

        Booking prima = service.crea("chiave-a", 1L, CustomerType.STANDARD, 1).prenotazione();
        Booking seconda = service.crea("chiave-b", 1L, CustomerType.STANDARD, 1).prenotazione();

        assertThat(prima.getSagaId()).isNotBlank();
        assertThat(prima.getSagaId()).isNotEqualTo(seconda.getSagaId());
        // lo stesso identificativo e' stato mandato a shows-service
        verify(showsClient).riserva(1L, 1, prima.getSagaId());
        verify(showsClient).riserva(1L, 1, seconda.getSagaId());
    }

    @Test
    @DisplayName("Se lo spettacolo non esiste ci si ferma subito: niente preventivo, niente riserva")
    void spettacoloInesistenteFermaTutto() {
        chiaveMaiVista();
        when(showsClient.perId(99L)).thenThrow(new SpettacoloNonTrovatoException(99L));

        assertThatThrownBy(() -> service.crea(CHIAVE, 99L, CustomerType.STANDARD, 2))
                .isInstanceOf(SpettacoloNonTrovatoException.class);

        verifyNoInteractions(pricingClient);
        verify(showsClient, never()).riserva(any(), anyInt(), anyString());
        // Il repository e' stato interrogato (passo 0, la chiave), ma niente
        // e' stato scritto: e' quello che conta.
        verify(repository, never()).save(any());
    }

    /**
     * Il caso piu' istruttivo: pricing non risponde DOPO che abbiamo letto lo
     * spettacolo ma PRIMA di aver riservato. Non c'e' niente da compensare —
     * il passo 1 e' una lettura, non ha cambiato niente — e quindi il fallimento
     * e' pulito.
     *
     * E' il motivo per cui l'ordine del passo 6.10 mette la riserva per terza:
     * tutto cio' che puo' fallire senza conseguenze, fallisce prima.
     */
    @Test
    @DisplayName("Se pricing non risponde non si riserva niente: il fallimento e' pulito")
    void pricingGiuNonRiservaNiente() {
        chiaveMaiVista();
        when(showsClient.perId(1L)).thenReturn(spettacolo);
        when(pricingClient.prezzoUnitario(any(), any(), anyBoolean()))
                .thenThrow(new ServizioNonDisponibileException("pricing-service", "timeout"));

        assertThatThrownBy(() -> service.crea(CHIAVE, 1L, CustomerType.STUDENT, 2))
                .isInstanceOf(ServizioNonDisponibileException.class);

        verify(showsClient, never()).riserva(any(), anyInt(), anyString());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Posti esauriti: l'eccezione sale intatta e non si salva niente")
    void postiEsauritiNonSalvaNiente() {
        chiaveMaiVista();
        when(showsClient.perId(1L)).thenReturn(spettacolo);
        when(pricingClient.prezzoUnitario(any(), any(), anyBoolean()))
                .thenReturn(new BigDecimal("10.00"));
        doThrow(new PostiEsauritiException(1L, 500))
                .when(showsClient).riserva(eq(1L), eq(500), anyString());

        assertThatThrownBy(() -> service.crea(CHIAVE, 1L, CustomerType.STUDENT, 500))
                .isInstanceOf(PostiEsauritiException.class);

        verify(repository, never()).save(any());
    }

    /**
     * ===================================================================
     * IL BUCO DEL G6, RESO ESPLICITO DA UN TEST.
     *
     * Il salvataggio fallisce DOPO che i posti sono stati riservati. Il
     * comportamento di oggi: l'eccezione sale, il cliente riceve un errore,
     * e NESSUNO rilascia i posti. Due poltrone perse.
     *
     * Il test lo afferma invece di limitarsi a subirlo: verifica che
     * rilascia() non venga chiamato. Sembra assurdo scrivere un test che
     * fissa un comportamento sbagliato, e invece e' il modo piu' onesto di
     * segnare dove si e' arrivati — al G8 questa riga diventera'
     *     verify(showsClient).rilascia(1L, 2, sagaId)
     * e il test raccontera' la storia del cambiamento.
     * ===================================================================
     */
    @Test
    @DisplayName("G6, il buco noto: se il salvataggio fallisce i posti restano riservati")
    void seIlSalvataggioFallisceIPostiRestanoRiservati() {
        chiaveMaiVista();
        when(showsClient.perId(1L)).thenReturn(spettacolo);
        when(pricingClient.prezzoUnitario(any(), any(), anyBoolean()))
                .thenReturn(new BigDecimal("10.00"));
        when(repository.save(any())).thenThrow(new RuntimeException("database pieno"));

        assertThatThrownBy(() -> service.crea(CHIAVE, 1L, CustomerType.STUDENT, 2))
                .isInstanceOf(RuntimeException.class);

        // i posti SONO stati riservati...
        verify(showsClient).riserva(eq(1L), eq(2), anyString());
        // ...e nessuno li rimette a posto. E' il problema del G8.
        verify(showsClient, never()).rilascia(any(), anyInt(), anyString());
    }

    // =====================================================================
    //  PASSO 7.6 — L'IDEMPOTENZA
    // =====================================================================

    /**
     * IL CASO PER CUI ESISTE TUTTO IL PASSO 7.6.
     *
     * L'utente ha premuto due volte. La seconda richiesta non deve creare
     * niente, e soprattutto non deve CHIAMARE niente: nessun preventivo,
     * nessuna riserva. Sono le tre chiamate HTTP risparmiate a ogni doppio
     * clic — e i due posti NON scalati una seconda volta.
     */
    @Test
    @DisplayName("Una chiave gia' vista restituisce la prenotazione di allora senza chiamare nessuno")
    void chiaveGiaVistaNonChiamaNessuno() {
        Booking diIeri = prenotazioneGiaEsistente();
        when(repository.findByIdempotencyKey(CHIAVE)).thenReturn(Optional.of(diIeri));

        EsitoPrenotazione esito = service.crea(CHIAVE, 1L, CustomerType.STUDENT, 2);

        assertThat(esito.giaEsistente()).isTrue();
        assertThat(esito.prenotazione()).isSameAs(diIeri);

        verifyNoInteractions(showsClient);
        verifyNoInteractions(pricingClient);
        verify(repository, never()).save(any());
    }

    /**
     * LA CORSA, cioe' il caso che il solo controllo applicativo non copre.
     *
     * Due richieste con la stessa chiave arrivano insieme: tutte e due
     * leggono "non c'e'", tutte e due proseguono, e il vincolo UNIQUE della
     * V2 ne ferma una sulla INSERT. Quella fermata non deve ricevere un
     * errore — la prenotazione che voleva esiste, l'ha appena scritta
     * l'altra.
     *
     * Si simula facendo rispondere al repository prima "vuoto" (il controllo
     * del passo 0) e poi, dopo il fallimento della save, la riga vincente.
     */
    @Test
    @DisplayName("La corsa persa sul vincolo UNIQUE diventa la prenotazione vincente, non un errore")
    void corsaPersaRestituisceLaVincente() {
        Booking vincitrice = prenotazioneGiaEsistente();
        when(repository.findByIdempotencyKey(CHIAVE))
                .thenReturn(Optional.empty())              // il controllo del passo 0
                .thenReturn(Optional.of(vincitrice));      // la rilettura dopo il vincolo
        when(showsClient.perId(1L)).thenReturn(spettacolo);
        when(pricingClient.prezzoUnitario(any(), any(), anyBoolean()))
                .thenReturn(new BigDecimal("10.00"));
        when(repository.save(any()))
                .thenThrow(new DataIntegrityViolationException("uk_bookings_idempotency_key"));

        EsitoPrenotazione esito = service.crea(CHIAVE, 1L, CustomerType.STUDENT, 2);

        assertThat(esito.giaEsistente()).isTrue();
        assertThat(esito.prenotazione()).isSameAs(vincitrice);
    }

    /**
     * Se il vincolo violato non e' quello della chiave, non e' una corsa: e'
     * qualcosa che non sappiamo spiegare, e si propaga con il suo stack
     * trace invece di diventare una risposta inventata.
     */
    @Test
    @DisplayName("Una violazione di vincolo che NON e' la chiave risale intatta")
    void violazioneDiAltroVincoloRisale() {
        when(repository.findByIdempotencyKey(CHIAVE)).thenReturn(Optional.empty());
        when(showsClient.perId(1L)).thenReturn(spettacolo);
        when(pricingClient.prezzoUnitario(any(), any(), anyBoolean()))
                .thenReturn(new BigDecimal("10.00"));
        when(repository.save(any()))
                .thenThrow(new DataIntegrityViolationException("uk_bookings_saga_id"));

        assertThatThrownBy(() -> service.crea(CHIAVE, 1L, CustomerType.STUDENT, 2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * La chiave finisce SULLA RIGA: e' l'unico modo per riconoscerla la
     * prossima volta. Sembra ovvio, e un save che la dimentica passerebbe
     * tutti gli altri test di questa classe.
     */
    @Test
    @DisplayName("La chiave del client finisce sulla prenotazione salvata")
    void laChiaveFinisceSullaRiga() {
        chiaveMaiVista();
        when(showsClient.perId(1L)).thenReturn(spettacolo);
        when(pricingClient.prezzoUnitario(any(), any(), anyBoolean()))
                .thenReturn(new BigDecimal("10.00"));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));

        EsitoPrenotazione esito = service.crea(CHIAVE, 1L, CustomerType.STUDENT, 2);

        assertThat(esito.giaEsistente()).isFalse();
        assertThat(esito.prenotazione().getIdempotencyKey()).isEqualTo(CHIAVE);
        // la chiave NON e' il sagaId: sono due identita' diverse (passo 7.6)
        assertThat(esito.prenotazione().getSagaId()).isNotEqualTo(CHIAVE);
    }

    @Test
    @DisplayName("Una chiave vuota o troppo lunga e' rifiutata prima di chiamare chiunque")
    void chiaveNonValidaFermaTutto() {
        assertThatThrownBy(() -> service.crea("   ", 1L, CustomerType.STUDENT, 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.crea("k".repeat(65), 1L, CustomerType.STUDENT, 2))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(showsClient, pricingClient, repository);
    }
}
