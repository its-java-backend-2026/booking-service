package it.its.cinema.bookingservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * PASSO 6.2 — booking-service, porta 8083, database booking_db.
 *
 * E' il servizio che CHIAMA gli altri due, e questo cambia tutto cio' che
 * gli serve addosso: i timeout del passo 6.6, la traduzione degli errori del
 * 6.9 e — dal G7 — circuit breaker e retry, che vanno messi su chi chiama e
 * non su chi viene chiamato.
 *
 * Niente @EnableFeignClients: qui si usa RestClient (passo 6.6). Feign sta in
 * shows-service, verso il fornitore esterno (passo 6.8b), cosi' nel corso si
 * vedono entrambi e si confrontano.
 */
@SpringBootApplication
public class BookingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(BookingServiceApplication.class, args);
    }

}
