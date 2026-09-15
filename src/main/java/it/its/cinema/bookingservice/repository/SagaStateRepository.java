package it.its.cinema.bookingservice.repository;

import it.its.cinema.bookingservice.domain.SagaState;
import it.its.cinema.bookingservice.domain.StatoSaga;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * PASSO 8.4 — l'accesso allo stato delle saghe.
 */
public interface SagaStateRepository extends JpaRepository<SagaState, Long> {

    Optional<SagaState> findByBookingId(Long bookingId);

    /**
     * ===================================================================
     * LA QUERY PER CUI ESISTE TUTTA LA TABELLA.
     *
     *     "quali saghe sono rimaste per strada?"
     *
     * Oggi non la chiama nessuno, ed e' voluto che ci sia lo stesso: e'
     * la dimostrazione, in una riga di codice, che una saga interrotta e'
     * diventata un dato TROVABILE invece di un guaio invisibile.
     *
     * Il lavoro periodico che la usera' — rileggere i passi raggiunti e
     * rieseguire le compensazioni mancanti — e' l'esercizio naturale dopo
     * il G8, e non richiede nessuna tabella nuova: tutto cio' che gli
     * serve e' gia' scritto qui.
     *
     * L'indice idx_saga_state_da_riprendere della V3 e' esattamente per
     * questa query, nell'ordine in cui filtra: prima lo stato, poi la data.
     * ===================================================================
     */
    List<SagaState> findByStatoAndAggiornataIlBefore(StatoSaga stato, LocalDateTime limite);
}
