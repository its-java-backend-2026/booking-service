package it.its.cinema.bookingservice.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import it.its.cinema.bookingservice.domain.CustomerType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * PASSO 6.10 — CIO' CHE IL CLIENT PUO' DECIDERE, E NIENT'ALTRO.
 *
 * Guardare cosa NON c'e': il prezzo, il titolo del film, l'orario, il sagaId.
 *
 * Il PREZZO soprattutto. La tentazione di accettarlo dal client ("tanto
 * gliel'abbiamo mostrato noi") e' la vulnerabilita' piu' classica di un
 * carrello: chiunque puo' rimandarci 0.01 al posto di 9.50. Il prezzo lo
 * chiediamo a pricing-service al passo 2, sempre, anche quando il client
 * crede di conoscerlo.
 *
 * Il sagaId invece non c'e' perche' lo generiamo NOI: e' l'identita' di
 * un'operazione che coordiniamo noi, e lasciarla scegliere a chi chiama
 * significherebbe permettergli di riusare quella di un altro.
 */
@Schema(description = "I dati per prenotare dei posti")
public record CreateBookingRequest(

        @NotNull(message = "Lo spettacolo e' obbligatorio")
        @Schema(description = "Identificativo dello spettacolo su shows-service",
                example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
        Long showId,

        /**
         * PASSO 8.2 — CHI STA COMPRANDO, e dal G8 serve davvero.
         *
         * Fino al G7 non c'era: una prenotazione non aveva bisogno di sapere
         * chi fosse il cliente. Dal G8 si': i punti fedelta' vanno accreditati
         * a qualcuno, e — soprattutto — vanno STORNATI a qualcuno quando la
         * saga compensa.
         *
         * Che arrivi dal client e' una semplificazione da aula, ed e' bene
         * dirlo: in un sistema vero l'identita' di chi compra si prende dal
         * token di autenticazione e non si accetta dal corpo della richiesta,
         * altrimenti chiunque puo' accreditare punti sul conto di un altro.
         * E' il G9, quando arrivera' il gateway.
         */
        @NotBlank(message = "Il cliente e' obbligatorio")
        @Size(max = 64, message = "Il cliente non puo' superare i 64 caratteri")
        @Schema(description = "Identificativo del cliente, per i punti fedelta'",
                example = "mario.rossi", requiredMode = Schema.RequiredMode.REQUIRED)
        String customerId,

        /**
         * L'enum nella firma: un valore non previsto non arriva al service.
         * Jackson non sa costruirlo, solleva HttpMessageNotReadable, e la
         * classe base di GestoreErrori risponde 400.
         */
        @NotNull(message = "La categoria del cliente e' obbligatoria")
        @Schema(description = "Categoria di chi acquista",
                example = "STUDENT", requiredMode = Schema.RequiredMode.REQUIRED)
        CustomerType customerType,

        /**
         * Integer e non int (passo 4.5): con il primitivo, un campo
         * dimenticato arriverebbe come 0 e il messaggio d'errore parlerebbe
         * di una quantita' non positiva invece che di un campo mancante.
         *
         * @Max non e' pignoleria: senza un tetto, una richiesta da due
         * miliardi di posti arriva fino a shows-service e gli fa fare lavoro
         * per niente. I limiti si mettono al confine, il piu' presto
         * possibile.
         */
        @NotNull(message = "La quantita' e' obbligatoria")
        @Positive(message = "La quantita' deve essere positiva")
        @Max(value = 20, message = "Non si possono prenotare piu' di 20 posti alla volta")
        @Schema(description = "Quanti posti prenotare, da 1 a 20",
                example = "2", requiredMode = Schema.RequiredMode.REQUIRED)
        Integer quantity) {
}
