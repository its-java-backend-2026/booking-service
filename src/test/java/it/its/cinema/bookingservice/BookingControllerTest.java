package it.its.cinema.bookingservice;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import it.its.cinema.bookingservice.domain.Booking;
import it.its.cinema.bookingservice.domain.CustomerType;
import it.its.cinema.bookingservice.domain.PostiEsauritiException;
import it.its.cinema.bookingservice.domain.ServizioNonDisponibileException;
import it.its.cinema.bookingservice.domain.SpettacoloNonTrovatoException;
import it.its.cinema.bookingservice.service.BookingService;
import it.its.cinema.bookingservice.web.BookingController;
import it.its.cinema.bookingservice.web.GestoreErrori;
import it.its.cinema.bookingservice.web.mapper.BookingMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PASSO 6.9 — IL TEST DELLA TABELLA DI TRADUZIONE.
 *
 * E' il test piu' importante di booking-service, perche' verifica l'unica
 * cosa che il chiamante vede davvero: che il guasto di un ALTRO servizio
 * arrivi con il codice giusto.
 *
 *     SpettacoloNonTrovatoException   ->  404
 *     PostiEsauritiException          ->  409
 *     ServizioNonDisponibileException ->  503 + Retry-After
 *
 * @WebMvcTest avvia SOLO la fetta web: niente database, niente RestClient,
 * niente container. BookingService e' un @MockitoBean perche' qui non
 * interessa cosa fa, interessa come viene raccontato cio' che solleva.
 *
 * ATTENZIONE Boot 4: l'annotazione @WebMvcTest sta in
 *     org.springframework.boot.webmvc.test.autoconfigure
 * e non piu' in org.springframework.boot.test.autoconfigure.web.servlet.
 * L'IDE, se l'import lo mette da solo, sbaglia.
 */
@WebMvcTest(BookingController.class)
@Import({GestoreErrori.class, BookingMapper.class})
@DisplayName("BookingController — il contratto HTTP")
class BookingControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    BookingService service;

    private static final String CORPO = """
            {"showId": 1, "customerType": "STUDENT", "quantity": 2}
            """;

    private Booking prenotazione() {
        return new Booking("3f2a1b9c-6d4e-4a7b-9c2f-1e8d0a5b7c31", 1L,
                CustomerType.STUDENT, 2, "Dune - Parte Due",
                LocalDateTime.of(2027, 1, 15, 21, 0), new BigDecimal("10.00"));
    }

    @Test
    @DisplayName("201 con Location, e il totale calcolato dal dominio")
    void creazioneRiuscita() throws Exception {
        when(service.crea(eq(1L), eq(CustomerType.STUDENT), eq(2)))
                .thenReturn(prenotazione());

        mockMvc.perform(post("/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPO))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.movieTitle").value("Dune - Parte Due"))
                .andExpect(jsonPath("$.unitPrice").value(10.00))
                .andExpect(jsonPath("$.totalPrice").value(20.00))
                .andExpect(jsonPath("$.sagaId").value("3f2a1b9c-6d4e-4a7b-9c2f-1e8d0a5b7c31"));

        // L'id qui e' null (il database non c'e'), quindi Location finisce con
        // "null": e' un limite del @WebMvcTest, non del codice. Che l'header
        // ci sia, pero', si verifica lo stesso.
        mockMvc.perform(post("/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPO))
                .andExpect(header().exists("Location"));
    }

    @Test
    @DisplayName("Il 404 di shows-service resta un 404")
    void spettacoloInesistente() throws Exception {
        when(service.crea(any(), any(), anyInt()))
                .thenThrow(new SpettacoloNonTrovatoException(99L));

        mockMvc.perform(post("/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPO))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Spettacolo non trovato"))
                .andExpect(jsonPath("$.type").value("https://cinema.its.it/errori/spettacolo-non-trovato"));
    }

    @Test
    @DisplayName("Il 409 di shows-service resta un 409, non diventa un 400")
    void postiEsauriti() throws Exception {
        when(service.crea(any(), any(), anyInt()))
                .thenThrow(new PostiEsauritiException(1L, 2));

        mockMvc.perform(post("/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPO))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Posti insufficienti"));
    }

    /**
     * IL TEST CHE VALE IL PASSO 6.9.
     *
     * Se qui uscisse 500, staremmo dicendo al mondo "abbiamo un bug" ogni
     * volta che e' qualcun altro a non rispondere: allarmi sbagliati, persone
     * sbagliate svegliate, e un client convinto che riprovare sia inutile.
     */
    @Test
    @DisplayName("Un servizio a valle giu' e' un 503 con Retry-After, MAI un 500")
    void servizioAValleGiu() throws Exception {
        when(service.crea(any(), any(), anyInt()))
                .thenThrow(new ServizioNonDisponibileException("pricing-service",
                        "HttpConnectTimeoutException: HTTP connect timed out"));

        mockMvc.perform(post("/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPO))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "10"))
                .andExpect(jsonPath("$.title").value("Servizio non disponibile"))
                .andExpect(jsonPath("$.servizio").value("pricing-service"));
    }

    @Test
    @DisplayName("Una quantita' oltre il tetto e' un 400, e non arriva al service")
    void quantitaOltreIlTetto() throws Exception {
        mockMvc.perform(post("/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"showId": 1, "customerType": "STUDENT", "quantity": 500}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.quantity").exists());

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("Una categoria sconosciuta e' un 400, non un 500")
    void categoriaSconosciuta() throws Exception {
        mockMvc.perform(post("/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"showId": 1, "customerType": "VIP", "quantity": 2}
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    /**
     * PASSO 6.10 — il prezzo NON si accetta dal client.
     *
     * Il campo non esiste in CreateBookingRequest, quindi Jackson lo ignora e
     * la prenotazione viene creata con il prezzo che ha detto pricing-service.
     * E' la stessa lezione del passo 4.1 su id e availableSeats, ma qui non
     * costa un dato incoerente: costa denaro.
     */
    @Test
    @DisplayName("Un prezzo mandato dal client viene ignorato, non accettato")
    void ilPrezzoDalClientVieneIgnorato() throws Exception {
        when(service.crea(eq(1L), eq(CustomerType.STUDENT), eq(2)))
                .thenReturn(prenotazione());

        mockMvc.perform(post("/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"showId": 1, "customerType": "STUDENT", "quantity": 2,
                                 "unitPrice": 0.01, "totalPrice": 0.02}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.unitPrice").value(10.00))
                .andExpect(jsonPath("$.totalPrice").value(20.00));

        // il service riceve i tre campi veri, e nient'altro
        verify(service, org.mockito.Mockito.atLeastOnce())
                .crea(1L, CustomerType.STUDENT, 2);
    }
}
