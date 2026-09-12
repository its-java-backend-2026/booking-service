package it.its.cinema.bookingservice.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * PASSO 6.8 — DTO DI CONFINE: LA COPIA LOCALE DEL CONTRATTO DI shows-service.
 *
 * E' la forma della risposta di GET /shows/{id}, e nient'altro. Non e'
 * l'entita' Show di shows-service (che ha annotazioni JPA e un @Version) e
 * non e' il nostro Booking: e' cio' che ci manda qualcun altro.
 *
 * NON E' UN MODULO CONDIVISO, ED E' IL PUNTO DEL PASSO.
 * Un jar cinema-contracts con dentro questo record sembra far risparmiare
 * codice, e in cambio lega i due servizi: cambiarlo impone di rilasciarli
 * insieme, che e' esattamente cio' che i microservizi servono a evitare.
 * Un database condiviso travestito da dipendenza Maven.
 *
 * ---------------------------------------------------------------------------
 * @JsonIgnoreProperties(ignoreUnknown = true) NON E' PIGRIZIA, E' IL CONTRATTO.
 *
 * shows-service restituisce nove campi; a noi ne servono quattro. Senza questa
 * annotazione, il giorno in cui shows aggiunge un campo — un'operazione che
 * per lui e' compatibile e che non ci avvisera' di fare — la nostra
 * deserializzazione fallisce con UnrecognizedPropertyException e le
 * prenotazioni si fermano.
 *
 * "Sii conservativo in cio' che mandi, liberale in cio' che accetti."
 * Ignorare i campi che non conosciamo e' cio' che permette ai due servizi di
 * evolvere a velocita' diverse.
 * ---------------------------------------------------------------------------
 *
 * Si prende SOLO cio' che serve. availableSeats non c'e' di proposito: e'
 * un dato che invecchia nel tempo fra la lettura e la riserva, e leggerlo
 * qui inviterebbe a controllarlo noi. La disponibilita' la decide chi la
 * possiede, dentro la SUA transazione, al passo 3 della saga.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ShowJson(
        Long id,
        String movieTitle,
        LocalDateTime startTime,
        BigDecimal basePrice,
        boolean eveningShow) {
}
