package it.its.cinema.bookingservice.repository;

import it.its.cinema.bookingservice.domain.Booking;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * PASSO 6.2 — l'accesso a booking_db.
 *
 * Nessun @EntityGraph e nessuna fetch join, a differenza di ShowRepository:
 * Booking non ha relazioni da caricare. Titolo e orario sono colonne sue
 * (passo 6.3), non un'altra entita' — ed e' il motivo per cui qui non esiste
 * il problema N+1 e non serve nessuna precauzione contro
 * LazyInitializationException.
 */
public interface BookingRepository extends JpaRepository<Booking, Long> {

    /**
     * Cercare per sagaId oggi non serve a nessuno, e al G8 servira' a tutto:
     * e' la domanda "questa saga l'ho gia' eseguita?" con cui si rende
     * idempotente la prenotazione. Sta qui perche' il vincolo UNIQUE che la
     * rende sensata e' gia' nella V1.
     */
    Optional<Booking> findBySagaId(String sagaId);

    /**
     * PASSO 7.6 — la domanda "questa richiesta l'ho gia' vista?".
     *
     * E' il controllo APPLICATIVO dell'idempotenza, e da solo non basta: due
     * richieste con la stessa chiave che arrivano insieme rispondono
     * entrambe "no" e proseguono entrambe. Serve lo stesso, ed e' il caso
     * normale: risparmia tre chiamate HTTP a ogni doppio clic. A decidere
     * davvero, nel millisecondo in cui si scrive, e' il vincolo UNIQUE della
     * V2 — e il service lo tratta come una risposta, non come un errore.
     */
    Optional<Booking> findByIdempotencyKey(String idempotencyKey);
}
