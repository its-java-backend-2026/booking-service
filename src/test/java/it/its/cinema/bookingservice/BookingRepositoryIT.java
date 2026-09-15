package it.its.cinema.bookingservice;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import it.its.cinema.bookingservice.domain.Booking;
import it.its.cinema.bookingservice.domain.CustomerType;
import it.its.cinema.bookingservice.repository.BookingRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PASSI 6.2 e 7.6 — LA PROVA CHE LE MIGRAZIONI E L'ENTITA' DICONO LA STESSA
 * COSA.
 *
 * Si chiama *IT e non *Test: lo esegue failsafe su "mvn verify", non surefire
 * su "mvn test". Cosi' "mvn test" resta veloce e non pretende Docker acceso.
 *
 * PostgreSQL VERO con Testcontainers: H2 non e' PostgreSQL, e la nostra
 * migrazione usa BIGSERIAL e NUMERIC.
 *
 * ---------------------------------------------------------------------------
 * IL TEST PIU' UTILE DI QUESTO FILE E' QUELLO CHE NON SI VEDE.
 *
 * Con ddl-auto: validate, il solo avvio del contesto confronta l'entita'
 * Booking con lo schema creato da Flyway. Un @Column(length = 200) contro un
 * VARCHAR(100), un campo aggiunto all'entita' e dimenticato in una V2, un
 * tipo che non combacia: l'applicazione NON PARTE, qui, invece che alle nove
 * di sera del primo rilascio.
 *
 * Dal G7 quel controllo vale il doppio: idempotencyKey e' stato aggiunto
 * all'entita' E alla V2, e se una delle due mani si fosse dimenticata
 * dell'altra questo file non arriverebbe nemmeno al primo @Test.
 *
 * Per questo un @SpringBootTest con un container vero vale piu' di dieci
 * @DataJpaTest su H2, che direbbero che va tutto bene.
 * ---------------------------------------------------------------------------
 *
 * @ServiceConnection sostituisce le vecchie @DynamicPropertySource: prende
 * url, utente e password dal container e li mette nel contesto da solo.
 *
 * Le due url dei servizi a valle sono fissate a un indirizzo che non
 * risponde: questo test non ne chiama nessuno, e un default puntato sul
 * portatile di chi lo lancia renderebbe l'esito dipendente da cio' che
 * quella persona ha acceso in quel momento.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "cinema.shows.url=http://localhost:1",
        "cinema.pricing.url=http://localhost:1"
})
@DisplayName("booking_db — lo schema della V1 e della V2")
class BookingRepositoryIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    BookingRepository repository;

    /** SQL diretto, per provare cio' che sta SOTTO a JPA. */
    @Autowired
    JdbcTemplate jdbc;

    private Booking nuova(String sagaId) {
        // chiave e sagaId coincidono quando non interessa distinguerli
        return nuova(sagaId, sagaId);
    }

    private Booking nuova(String chiaveIdempotenza, String sagaId) {
        return new Booking(chiaveIdempotenza, sagaId, 1L, CustomerType.STUDENT, 2,
                "Dune - Parte Due",
                LocalDateTime.of(2027, 1, 15, 21, 0),
                new BigDecimal("10.00"));
    }

    @Test
    @DisplayName("Una prenotazione si salva e si rilegge identica")
    void salvaERilegge() {
        Booking salvata = repository.save(nuova("saga-uno"));

        assertThat(salvata.getId()).isNotNull();

        Booking riletta = repository.findById(salvata.getId()).orElseThrow();
        assertThat(riletta.getMovieTitle()).isEqualTo("Dune - Parte Due");
        assertThat(riletta.getStartTime()).isEqualTo(LocalDateTime.of(2027, 1, 15, 21, 0));
        // NUMERIC(8,2) conserva la scala: il prezzo resta 10.00, non 10
        assertThat(riletta.getUnitPrice()).isEqualByComparingTo("10.00");
        assertThat(riletta.getTotalPrice()).isEqualByComparingTo("20.00");
        assertThat(riletta.getCustomerType()).isEqualTo(CustomerType.STUDENT);
    }

    /**
     * PASSO 6.4 — il vincolo che al G8 rendera' la saga idempotente.
     *
     * Si verifica oggi, quando non serve ancora, perche' un vincolo che non
     * si e' mai visto scattare e' un vincolo di cui non si sa se funziona.
     */
    @Test
    @DisplayName("Due prenotazioni con lo stesso sagaId non possono coesistere")
    void sagaIdUnico() {
        repository.saveAndFlush(nuova("chiave-a", "saga-doppia"));

        assertThatThrownBy(() -> repository.saveAndFlush(nuova("chiave-b", "saga-doppia")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * ===================================================================
     * PASSO 7.6 — IL VINCOLO CHE REGGE L'IDEMPOTENZA, VISTO SCATTARE.
     *
     * E' la protezione VERA, quella che il controllo in Java non puo'
     * dare: due richieste con la stessa chiave arrivate nello stesso
     * istante leggono entrambe "non c'e'" e proseguono entrambe, e a
     * quel punto l'unico posto che le tre istanze del servizio
     * condividono e' questo.
     *
     * Verificarlo qui, a freddo, e' l'unico modo di sapere che c'e'
     * davvero: nel flusso normale della POST non lo si vede mai, perche'
     * BookingService lo intercetta e lo trasforma nella prenotazione
     * vincente.
     * ===================================================================
     */
    @Test
    @DisplayName("Due prenotazioni con la stessa Idempotency-Key non possono coesistere")
    void chiaveDiIdempotenzaUnica() {
        repository.saveAndFlush(nuova("chiave-ripetuta", "saga-uno-di-due"));

        assertThatThrownBy(() ->
                repository.saveAndFlush(nuova("chiave-ripetuta", "saga-due-di-due")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** La riga si ritrova per chiave: e' la query del passo 0 di BookingService. */
    @Test
    @DisplayName("Una prenotazione si ritrova dalla sua Idempotency-Key")
    void siRitrovaPerChiave() {
        Booking salvata = repository.saveAndFlush(nuova("chiave-da-ritrovare", "saga-x"));

        assertThat(repository.findByIdempotencyKey("chiave-da-ritrovare"))
                .get()
                .extracting(Booking::getId)
                .isEqualTo(salvata.getId());

        assertThat(repository.findByIdempotencyKey("chiave-mai-vista")).isEmpty();
    }

    /**
     * Il CHECK della V1, non una validazione Java.
     *
     * Il costruttore di Booking rifiuta gia' le quantita' non positive, quindi
     * per arrivare al database bisogna scavalcarlo. Non lo si fa: si verifica
     * invece che il database sia l'ultima linea di difesa per la strada che
     * NON passa dal nostro codice — uno script, una psql aperta, un'altra
     * istanza del servizio con una versione diversa.
     *
     * Qui lo si prova con una UPDATE diretta, in SQL, saltando JPA:
     * e' il modo piu' vicino a "un collega con psql aperto".
     */
    @Test
    @DisplayName("Il database rifiuta una quantita' non positiva anche scavalcando il dominio")
    void ilCheckDelDatabaseTiene() {
        Booking salvata = repository.saveAndFlush(nuova("saga-check"));

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE bookings SET quantity = 0 WHERE id = ?", salvata.getId()))
                .isInstanceOf(DataAccessException.class);
    }
}
