package it.its.cinema.bookingservice.web;

import java.net.URI;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import it.its.cinema.bookingservice.domain.Booking;
import it.its.cinema.bookingservice.service.BookingService;
import it.its.cinema.bookingservice.web.dto.BookingResponse;
import it.its.cinema.bookingservice.web.dto.CreateBookingRequest;
import it.its.cinema.bookingservice.web.mapper.BookingMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PASSO 6.10 — LA ROTTA CHE ATTRAVERSA TRE PROCESSI.
 *
 * Il controller non se ne accorge, ed e' il segno che gli strati sono al
 * posto giusto: qui non compare nessun RestClient, nessun URL, nessun codice
 * di stato altrui. Chiama il service e traduce in HTTP, esattamente come
 * faceva al G1 quando i dati stavano in una mappa in memoria.
 *
 * Tutta la differenza fra "un servizio" e "tre servizi" sta nel service e nei
 * due gateway. E' quello che permettera' al G8 di cambiare l'orchestrazione
 * senza toccare questa classe.
 */
@RestController
@RequestMapping("/bookings")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService service;
    private final BookingMapper mapper;

    /**
     * CONSEGNA G6 — una POST /bookings crea la prenotazione con il prezzo
     * corretto e i posti scalati, attraversando tre processi.
     *
     * I quattro esiti, e perche' sono quelli:
     *
     *   201  fatto: posti scalati, prenotazione salvata, Location valorizzata
     *   404  lo spettacolo non esiste          (il 404 di shows, passo 6.9)
     *   409  i posti non bastano               (il 409 di shows, passo 6.9)
     *   503  un servizio a valle non risponde  (5xx o timeout, NON un 500)
     */
    @Operation(summary = "Prenota dei posti",
            description = "Legge lo spettacolo da shows-service, chiede il prezzo a "
                    + "pricing-service, riserva i posti e registra la prenotazione. "
                    + "Il prezzo NON si accetta dal client: si chiede sempre.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Prenotazione creata, con header Location",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = BookingResponse.class))),
            @ApiResponse(responseCode = "400", description = "Dati non validi",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Lo spettacolo non esiste",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "Posti insufficienti",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503",
                    description = "shows-service o pricing-service non hanno risposto",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping
    public ResponseEntity<BookingResponse> prenota(
            @Valid @RequestBody CreateBookingRequest richiesta) {

        Booking creata = service.crea(
                richiesta.showId(),
                richiesta.customerType(),
                richiesta.quantity());

        // 201 con Location: il client sa dove e' finita la risorsa che ha
        // creato, senza doverla cercare. E' meta' del significato di "created".
        return ResponseEntity.created(URI.create("/bookings/" + creata.getId()))
                .body(mapper.toResponse(creata));
    }

    @Operation(summary = "Cerca una prenotazione per ID",
            description = "Non chiama nessun altro servizio: titolo e orario sono "
                    + "dati nostri dal passo 6.3. Funziona anche con shows-service spento.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Prenotazione trovata",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = BookingResponse.class))),
            @ApiResponse(responseCode = "404", description = "Prenotazione non trovata",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/{id}")
    public BookingResponse perId(
            @Parameter(description = "Identificativo della prenotazione", example = "1")
            @PathVariable Long id) {
        return mapper.toResponse(service.perId(id));
    }

    /** Elenco paginato, con la stessa regola del passo 3.4: mai senza limiti. */
    @Operation(summary = "Elenca le prenotazioni",
            description = "Elenco paginato, dalla piu' recente. "
                    + "Parametri: page, size, sort (es. createdAt,asc).")
    @ApiResponses(@ApiResponse(responseCode = "200", description = "Pagina restituita, anche vuota",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = BookingResponse.class))))
    @GetMapping
    public Page<BookingResponse> elenco(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return mapper.toResponse(service.elenco(pageable));
    }
}
