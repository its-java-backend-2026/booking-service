package it.its.cinema.bookingservice.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * PASSO 6.10 — il biglietto, come lo legge chi lo ha comprato.
 *
 * Contiene titolo e orario, e per rispondere NON si chiama shows-service:
 * sono colonne nostre dal passo 6.3. E' il vantaggio concreto della copia —
 * questa rotta funziona anche con shows-service spento.
 *
 * Il sagaId esce nella risposta di proposito: e' la stringa da citare in
 * un'assistenza ("la mia prenotazione non risulta") per ritrovare l'intera
 * operazione nei log di tutti e tre i servizi.
 */
@Schema(description = "Una prenotazione confermata")
public record BookingResponse(

        @Schema(description = "Identificativo della prenotazione", example = "1")
        Long id,

        @Schema(description = "Identificativo dell'operazione di acquisto, "
                + "utile per ritrovarla nei log di tutti i servizi",
                example = "3f2a1b9c-6d4e-4a7b-9c2f-1e8d0a5b7c31")
        String sagaId,

        @Schema(description = "Identificativo dello spettacolo su shows-service", example = "1")
        Long showId,

        @Schema(description = "Titolo del film al momento dell'acquisto",
                example = "Dune - Parte Due")
        String movieTitle,

        @Schema(description = "Orario dello spettacolo al momento dell'acquisto",
                example = "2027-01-15T21:00:00")
        LocalDateTime startTime,

        @Schema(description = "Categoria applicata", example = "STUDENT")
        String customerType,

        @Schema(description = "Posti prenotati", example = "2")
        int quantity,

        @Schema(description = "Prezzo pagato per UN biglietto", example = "10.00")
        BigDecimal unitPrice,

        @Schema(description = "Totale pagato", example = "20.00")
        BigDecimal totalPrice,

        @Schema(description = "Quando e' stata registrata", example = "2026-09-12T15:04:11")
        LocalDateTime createdAt) {
}
