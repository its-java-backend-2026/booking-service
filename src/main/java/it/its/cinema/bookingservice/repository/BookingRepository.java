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
}
