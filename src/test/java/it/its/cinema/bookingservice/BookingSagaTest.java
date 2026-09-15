package it.its.cinema.bookingservice;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import it.its.cinema.bookingservice.client.LoyaltyClient;
import it.its.cinema.bookingservice.client.PaymentClient;
import it.its.cinema.bookingservice.client.ShowsClient;
import it.its.cinema.bookingservice.domain.Booking;
import it.its.cinema.bookingservice.domain.CustomerType;
import it.its.cinema.bookingservice.domain.PagamentoRifiutatoException;
import it.its.cinema.bookingservice.domain.PassoSaga;
import it.its.cinema.bookingservice.domain.SagaState;
import it.its.cinema.bookingservice.domain.ServizioNonDisponibileException;
import it.its.cinema.bookingservice.service.BookingSaga;
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
import org.springframework.dao.DataAccessResourceFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * PASSI 8.5 e 8.7 — IL TEST DELLA SAGA, ED E' IL PIU' IMPORTANTE DEL G8.
 *
 * ===========================================================================
 * PERCHE' UN TEST A OGGETTI FINTI E' IL POSTO GIUSTO PER PROVARE UNA SAGA.
 *
 * Cio' che si sta verificando non e' che le chiamate HTTP funzionino — quello
 * lo fa SagaIT con dei servizi veri — ma che DAVANTI A UN FALLIMENTO si torni
 * indietro nel modo giusto:
 *
 *   - la compensazione tocca solo cio' che era gia' stato fatto
 *   - va all'indietro
 *   - una compensazione che fallisce non ferma le altre
 *   - l'eccezione arriva comunque al chiamante
 *
 * Sono i quattro casi che in esercizio si verificano quando il sistema e'
 * gia' in difficolta', cioe' quando nessuno ha voglia di scoprirli. Con i
 * mock si provocano tutti in un millisecondo, e non c'e' altro modo di
 * provocare a comando "loyalty-service esplode DURANTE la compensazione".
 * ===========================================================================
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BookingSaga — l'orchestrazione (8.5) e le compensazioni (8.7)")
class BookingSagaTest {

    private static final String CLIENTE = "mario.rossi";

    @Mock
    ShowsClient showsClient;

    @Mock
    PaymentClient paymentClient;

    @Mock
    LoyaltyClient loyaltyClient;

    @Mock
    SagaStore store;

    @InjectMocks
    BookingSaga saga;

    private Booking prenotazione;
    private SagaState stato;

    @BeforeEach
    void setUp() {
        prenotazione = new Booking("chiave-1", "saga-1", 7L, CustomerType.STUDENT, 2,
                "Dune - Parte Due", LocalDateTime.of(2027, 1, 15, 21, 0),
                new BigDecimal("10.00"));
        stato = new SagaState(1L, CLIENTE, 7L, 2, 20);

        // store.avanza fa avanzare l'oggetto e lo restituisce, come farebbe
        // il vero SagaStore dopo aver scritto sul database.
        lenientAvanza();
    }

    private void lenientAvanza() {
        org.mockito.Mockito.lenient().when(store.avanza(any(), any()))
                .thenAnswer(invocazione -> {
                    SagaState s = invocazione.getArgument(0);
                    s.avanza(invocazione.getArgument(1));
                    return s;
                });
        org.mockito.Mockito.lenient().when(store.conferma(any(), any()))
                .thenAnswer(invocazione -> invocazione.getArgument(0));
        org.mockito.Mockito.lenient().when(store.fallisci(any()))
                .thenAnswer(invocazione -> invocazione.getArgument(0));
    }

    // =====================================================================
    // IL PERCORSO FELICE
    // =====================================================================

    @Test
    @DisplayName("PASSO 8.5 — i tre passi avvengono in ordine, e la saga si chiude")
    void iTrePassiInOrdine() {
        saga.esegui(prenotazione, stato);

        InOrder ordine = inOrder(showsClient, paymentClient, loyaltyClient, store);
        ordine.verify(showsClient).riserva(7L, 2, "saga-1");
        ordine.verify(store).avanza(any(), eq(PassoSaga.POSTI_RISERVATI));
        ordine.verify(paymentClient).autorizza("saga-1", null, new BigDecimal("20.00"));
        ordine.verify(store).avanza(any(), eq(PassoSaga.PAGATO));
        ordine.verify(loyaltyClient).accredita("saga-1", CLIENTE, 20);
        ordine.verify(store).avanza(any(), eq(PassoSaga.PUNTI_ACCREDITATI));
        ordine.verify(store).conferma(any(), any());
    }

    @Test
    @DisplayName("Quando va tutto bene non si compensa niente")
    void nienteCompensazioneSeVaTuttoBene() {
        saga.esegui(prenotazione, stato);

        verify(showsClient, never()).rilascia(anyLong(), anyInt(), anyString());
        verify(paymentClient, never()).storna(anyString());
        verify(loyaltyClient, never()).storna(anyString(), anyString(), anyInt());
        verify(store, never()).compensata(any(), anyBoolean(), anyString());
    }

    @Test
    @DisplayName("Un biglietto da zero punti non chiama loyalty, e non lo compensera'")
    void zeroPuntiNonChiamaLoyalty() {
        SagaState senzaPunti = new SagaState(1L, CLIENTE, 7L, 2, 0);

        saga.esegui(prenotazione, senzaPunti);

        verifyNoInteractions(loyaltyClient);
        // Il passo raggiunto resta PAGATO: e' la verita', e la compensazione
        // di un'altra saga non dovra' stornare punti mai dati.
        assertThat(senzaPunti.getPassoRaggiunto()).isEqualTo(PassoSaga.PAGATO);
    }

    // =====================================================================
    // IL FALLIMENTO DEL PASSO 8.8: IL PAGAMENTO DICE DI NO
    // =====================================================================

    @Test
    @DisplayName("PASSO 8.8 — pagamento rifiutato: i posti tornano, e il 402 arriva al client")
    void pagamentoRifiutatoRilasciaIPosti() {
        doThrow(new PagamentoRifiutatoException("saga-1", "oltre la soglia"))
                .when(paymentClient).autorizza(anyString(), any(), any());

        assertThatThrownBy(() -> saga.esegui(prenotazione, stato))
                .isInstanceOf(PagamentoRifiutatoException.class);

        // ===================================================================
        // LA CONSEGNA DEL G8, IN TRE RIGHE: un pagamento rifiutato non lascia
        // mai posti bloccati.
        // ===================================================================
        verify(showsClient).rilascia(7L, 2, "saga-1");

        // E NON si storna cio' che non e' mai avvenuto: il pagamento non e'
        // passato, i punti non sono stati dati.
        verify(paymentClient, never()).storna(anyString());
        verify(loyaltyClient, never()).storna(anyString(), anyString(), anyInt());

        verify(store).compensata(any(), eq(true), anyString());
        verify(store).fallisci(prenotazione);
    }

    @Test
    @DisplayName("PASSO 8.7 — si compensa ALL'INDIETRO: punti, pagamento, posti")
    void laCompensazioneVaAllIndietro() {
        // ===================================================================
        // FALLISCE LA SCRITTURA LOCALE, DOPO CHE TUTTO IL RESTO E' RIUSCITO.
        //
        // E' il caso in cui c'e' il massimo da compensare — tre passi su tre
        // — ed e' anche il piu' istruttivo: il guasto non e' di nessuno dei
        // servizi remoti, e' il NOSTRO database che non risponde al momento
        // di confermare. Anche li' bisogna saper tornare indietro, e la
        // saga non fa nessuna differenza fra i due casi.
        // ===================================================================
        when(store.conferma(any(), any()))
                .thenThrow(new DataAccessResourceFailureException("booking_db non risponde"));

        assertThatThrownBy(() -> saga.esegui(prenotazione, stato))
                .isInstanceOf(DataAccessResourceFailureException.class);

        InOrder ordine = inOrder(loyaltyClient, paymentClient, showsClient);
        ordine.verify(loyaltyClient).storna("saga-1", CLIENTE, 20);
        ordine.verify(paymentClient).storna("saga-1");
        // I posti per ULTIMI: sono la risorsa piu' contesa, e rilasciarli
        // prima significherebbe darli via mentre si sta ancora stornando un
        // pagamento che potrebbe fallire.
        ordine.verify(showsClient).rilascia(7L, 2, "saga-1");
    }

    @Test
    @DisplayName("PASSO 8.7 — si compensa SOLO cio' che era stato fatto")
    void soloCioCheEraStatoFatto() {
        // Fallisce il PRIMO passo: non c'e' niente da compensare.
        doThrow(new ServizioNonDisponibileException("shows-service", "timeout"))
                .when(showsClient).riserva(anyLong(), anyInt(), anyString());

        assertThatThrownBy(() -> saga.esegui(prenotazione, stato))
                .isInstanceOf(ServizioNonDisponibileException.class);

        verify(showsClient, never()).rilascia(anyLong(), anyInt(), anyString());
        verifyNoInteractions(paymentClient, loyaltyClient);

        // La saga si chiude come COMPENSATA lo stesso: non c'era niente da
        // rimettere a posto, ed e' un successo — non un caso da segnalare.
        verify(store).compensata(any(), eq(true), anyString());
    }

    // =====================================================================
    // I DUE CASI CHE FANNO LA DIFFERENZA (passo 8.7)
    // =====================================================================

    @Test
    @DisplayName("PASSO 8.7 — una compensazione che FALLISCE non impedisce le altre")
    void unaCompensazioneCheFallisceNonFermaLeAltre() {
        // Tutti e tre i passi sono riusciti, poi la conferma locale fallisce:
        // si compensa tutto. E mentre si compensa, loyalty-service va giu' —
        // che e' il momento peggiore e quindi quello da provare.
        when(store.conferma(any(), any()))
                .thenThrow(new DataAccessResourceFailureException("booking_db non risponde"));
        when(loyaltyClient.storna(anyString(), anyString(), anyInt()))
                .thenThrow(new ServizioNonDisponibileException("loyalty-service", "timeout"));

        assertThatThrownBy(() -> saga.esegui(prenotazione, stato))
                .isInstanceOf(DataAccessResourceFailureException.class);

        // ===================================================================
        // LE ALTRE DUE VENGONO ESEGUITE LO STESSO, ED E' IL PUNTO.
        //
        // Fermarsi alla prima compensazione fallita lascerebbe i posti
        // bloccati per sempre: il guasto peggiore, causato dal tentativo di
        // sistemare quello migliore.
        // ===================================================================
        verify(paymentClient).storna("saga-1");
        verify(showsClient).rilascia(7L, 2, "saga-1");

        // E la saga lo dice: COMPENSAZIONE_PARZIALE, non COMPENSATA.
        verify(store).compensata(any(), eq(false), anyString());
    }

    @Test
    @DisplayName("PASSO 8.2 — lo storno parziale dei punti rende la saga PARZIALE")
    void stornoParzialeDeiPunti() {
        when(store.conferma(any(), any()))
                .thenThrow(new DataAccessResourceFailureException("booking_db non risponde"));
        // Nessuna eccezione: loyalty risponde 200, ma ha potuto togliere solo
        // una parte dei punti perche' il cliente li aveva gia' spesi.
        when(loyaltyClient.storna(anyString(), anyString(), anyInt())).thenReturn(false);

        assertThatThrownBy(() -> saga.esegui(prenotazione, stato))
                .isInstanceOf(DataAccessResourceFailureException.class);

        // ===================================================================
        // IL CASO PIU' INSIDIOSO DI TUTTI: non c'e' nessun errore, nessuna
        // eccezione, nessuna riga rossa nei log. Eppure il sistema NON e'
        // tornato com'era, e solo lo stato della saga lo dice.
        // ===================================================================
        ArgumentCaptor<Boolean> completa = ArgumentCaptor.forClass(Boolean.class);
        verify(store).compensata(any(), completa.capture(), anyString());
        assertThat(completa.getValue()).isFalse();
    }

    @Test
    @DisplayName("L'eccezione arriva SEMPRE al chiamante, anche a compensazione riuscita")
    void lEccezioneRisaleSempre() {
        doThrow(new PagamentoRifiutatoException("saga-1", "oltre la soglia"))
                .when(paymentClient).autorizza(anyString(), any(), any());

        // Ingoiarla restituirebbe un 201 per un acquisto mai avvenuto: e' il
        // difetto piu' grave che un orchestratore possa avere, ed e' anche
        // il piu' facile da introdurre "per far funzionare un test".
        assertThatThrownBy(() -> saga.esegui(prenotazione, stato))
                .isInstanceOf(PagamentoRifiutatoException.class)
                .hasMessageContaining("oltre la soglia");
    }

    @Test
    @DisplayName("La prenotazione finisce FALLITA, e non viene cancellata")
    void laPrenotazioneRestaFallita() {
        doThrow(new PagamentoRifiutatoException("saga-1", "oltre la soglia"))
                .when(paymentClient).autorizza(anyString(), any(), any());

        assertThatThrownBy(() -> saga.esegui(prenotazione, stato))
                .isInstanceOf(PagamentoRifiutatoException.class);

        verify(store).fallisci(prenotazione);
        verify(store, never()).conferma(any(), any());
    }
}
