package it.its.cinema.bookingservice.web.mapper;

import it.its.cinema.bookingservice.domain.Booking;
import it.its.cinema.bookingservice.web.dto.BookingResponse;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

/**
 * PASSO 4.2 (applicato qui) — il mapper, scritto a mano.
 *
 * Sta nel package web e non nel service: il DTO e' un fatto del livello HTTP,
 * e il service non deve sapere che esiste.
 *
 * A differenza di ShowMapper, qui nessuna riga rischia una
 * LazyInitializationException: Booking non ha relazioni, tutto cio' che serve
 * e' gia' sulla riga. E' la conseguenza diretta del passo 6.3.
 */
@Component
public class BookingMapper {

    public BookingResponse toResponse(Booking b) {
        return new BookingResponse(
                b.getId(),
                b.getSagaId(),
                b.getShowId(),
                b.getMovieTitle(),
                b.getStartTime(),
                b.getCustomerType().name(),
                b.getQuantity(),
                b.getUnitPrice(),
                b.getTotalPrice(),
                b.getCreatedAt());
    }

    /**
     * Page.map conserva i metadati della pagina (totalElements, totalPages,
     * number, size) e cambia solo il contenuto: ricostruire una PageImpl a
     * mano significa quasi sempre perdere il totale, che e' il dato per cui
     * il client ha chiesto una pagina invece di una lista.
     */
    public Page<BookingResponse> toResponse(Page<Booking> bookings) {
        return bookings.map(this::toResponse);
    }
}
