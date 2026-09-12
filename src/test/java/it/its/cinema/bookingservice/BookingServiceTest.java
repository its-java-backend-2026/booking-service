package it.its.cinema.bookingservice;

import java.math.BigDecimal;
import java.time.LocalDateTime;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
@DisplayName("BookingService — la saga del passo 6.10")
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

    @BeforeEach
    void setUp() {
        spettacolo = new ShowJson(1L, "Dune - Parte Due",
                LocalDateTime.of(2027, 1, 15, 21, 0), new BigDecimal("10.00"), true);
    }

    @Test
    @DisplayName("I quattro passi avvengono nell'ordine del passo 6.10")
    void iQuattroPassiInOrdine() {
        when(showsClient.perId(1L)).thenReturn(spettacolo);
        when(pricingClient.prezzoUnitario(any(), any(), anyBoolean()))
                .thenReturn(new BigDecimal("10.00"));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.crea(1L, CustomerType.STUDENT, 2);

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
        when(showsClient.perId(1L)).thenReturn(spettacolo);
        when(pricingClient.prezzoUnitario(any(), any(), anyBoolean()))
                .thenReturn(new BigDecimal("10.00"));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));

        Booking creata = service.crea(1L, CustomerType.STUDENT, 2);

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
        when(showsClient.perId(1L)).thenReturn(spettacolo);
        when(pricingClient.prezzoUnitario(any(), any(), anyBoolean()))
                .thenReturn(new BigDecimal("10.00"));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));

        Booking prima = service.crea(1L, CustomerType.STANDARD, 1);
        Booking seconda = service.crea(1L, CustomerType.STANDARD, 1);

        assertThat(prima.getSagaId()).isNotBlank();
        assertThat(prima.getSagaId()).isNotEqualTo(seconda.getSagaId());
        // lo stesso identificativo e' stato mandato a shows-service
        verify(showsClient).riserva(1L, 1, prima.getSagaId());
        verify(showsClient).riserva(1L, 1, seconda.getSagaId());
    }

    @Test
    @DisplayName("Se lo spettacolo non esiste ci si ferma subito: niente preventivo, niente riserva")
    void spettacoloInesistenteFermaTutto() {
        when(showsClient.perId(99L)).thenThrow(new SpettacoloNonTrovatoException(99L));

        assertThatThrownBy(() -> service.crea(99L, CustomerType.STANDARD, 2))
                .isInstanceOf(SpettacoloNonTrovatoException.class);

        verifyNoInteractions(pricingClient);
        verify(showsClient, never()).riserva(any(), anyInt(), anyString());
        verifyNoInteractions(repository);
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
        when(showsClient.perId(1L)).thenReturn(spettacolo);
        when(pricingClient.prezzoUnitario(any(), any(), anyBoolean()))
                .thenThrow(new ServizioNonDisponibileException("pricing-service", "timeout"));

        assertThatThrownBy(() -> service.crea(1L, CustomerType.STUDENT, 2))
                .isInstanceOf(ServizioNonDisponibileException.class);

        verify(showsClient, never()).riserva(any(), anyInt(), anyString());
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("Posti esauriti: l'eccezione sale intatta e non si salva niente")
    void postiEsauritiNonSalvaNiente() {
        when(showsClient.perId(1L)).thenReturn(spettacolo);
        when(pricingClient.prezzoUnitario(any(), any(), anyBoolean()))
                .thenReturn(new BigDecimal("10.00"));
        doThrow(new PostiEsauritiException(1L, 500))
                .when(showsClient).riserva(eq(1L), eq(500), anyString());

        assertThatThrownBy(() -> service.crea(1L, CustomerType.STUDENT, 500))
                .isInstanceOf(PostiEsauritiException.class);

        verifyNoInteractions(repository);
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
        when(showsClient.perId(1L)).thenReturn(spettacolo);
        when(pricingClient.prezzoUnitario(any(), any(), anyBoolean()))
                .thenReturn(new BigDecimal("10.00"));
        when(repository.save(any())).thenThrow(new RuntimeException("database pieno"));

        assertThatThrownBy(() -> service.crea(1L, CustomerType.STUDENT, 2))
                .isInstanceOf(RuntimeException.class);

        // i posti SONO stati riservati...
        verify(showsClient).riserva(eq(1L), eq(2), anyString());
        // ...e nessuno li rimette a posto. E' il problema del G8.
        verify(showsClient, never()).rilascia(any(), anyInt(), anyString());
    }
}
