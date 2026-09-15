package it.its.cinema.bookingservice.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * PASSO 8.4 — LO STATO DELLA SAGA, CHE E' UN OGGETTO E NON UNA VARIABILE
 * LOCALE.
 *
 * ===========================================================================
 * PERCHE' NON BASTA TENERLO IN MEMORIA.
 *
 * L'orchestratore potrebbe benissimo ricordarsi da solo a che punto e'
 * arrivato: una variabile nel metodo, e la compensazione la legge. Funziona
 * per tutta la durata della chiamata, e smette di funzionare nell'unico
 * momento in cui servirebbe davvero — quando il processo muore fra un passo
 * e l'altro.
 *
 * In quel momento la variabile non esiste piu', e con lei l'unica
 * informazione che dice cosa c'era da rimettere a posto. I posti restano
 * scalati, il pagamento resta preso, e nessuno lo sa.
 *
 * Su una riga invece resta scritto, e diventa una domanda che si puo' fare
 * al database: "quali saghe sono IN_CORSO da piu' di cinque minuti?".
 * ===========================================================================
 *
 * NESSUNA @ManyToOne verso Booking, e stavolta non per la regola dei
 * microservizi (le due tabelle stanno nello stesso database): e' che questa
 * riga deve poter essere letta e riusata DA SOLA, senza tirarsi dietro la
 * prenotazione. Chi riprendera' le saghe interrotte vuole i parametri della
 * compensazione, non il biglietto.
 */
@Entity
@Table(name = "saga_state")
@Getter
@NoArgsConstructor
public class SagaState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "booking_id", nullable = false, unique = true)
    private Long bookingId;

    @Column(name = "customer_id", nullable = false, length = 64)
    private String customerId;

    // I parametri con cui la compensazione dovra' essere rieseguita.
    @Column(name = "show_id", nullable = false)
    private Long showId;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "loyalty_points", nullable = false)
    private int loyaltyPoints;

    /** STRING e mai ORDINAL: vedi il commento in PassoSaga. */
    @Enumerated(EnumType.STRING)
    @Column(name = "passo_raggiunto", nullable = false, length = 30)
    private PassoSaga passoRaggiunto;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private StatoSaga stato;

    @Column(name = "ultimo_errore", length = 500)
    private String ultimoErrore;

    @Column(name = "aggiornata_il", nullable = false)
    private LocalDateTime aggiornataIl;

    public SagaState(Long bookingId, String customerId, Long showId,
                     int quantity, int loyaltyPoints) {
        if (bookingId == null) {
            throw new IllegalArgumentException("Il bookingId e' obbligatorio");
        }
        if (customerId == null || customerId.isBlank()) {
            throw new IllegalArgumentException("Il customerId e' obbligatorio");
        }
        if (showId == null) {
            throw new IllegalArgumentException("Lo spettacolo e' obbligatorio");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("La quantita' deve essere positiva");
        }
        if (loyaltyPoints < 0) {
            throw new IllegalArgumentException("I punti non possono essere negativi");
        }
        this.bookingId = bookingId;
        this.customerId = customerId;
        this.showId = showId;
        this.quantity = quantity;
        this.loyaltyPoints = loyaltyPoints;
        this.passoRaggiunto = PassoSaga.AVVIATA;
        this.stato = StatoSaga.IN_CORSO;
        this.aggiornataIl = LocalDateTime.now();
    }

    /**
     * PASSO 8.5 — si avanza di un passo, e si scrive SUBITO.
     *
     * Non si aspetta la fine per registrare il percorso: se si aspettasse,
     * un processo che muore a meta' lascerebbe la riga a "AVVIATA" e la
     * compensazione non saprebbe che c'erano dei posti da rilasciare.
     * Il passo si scrive DOPO che e' avvenuto e PRIMA del successivo.
     */
    public void avanza(PassoSaga passo) {
        this.passoRaggiunto = passo;
        this.aggiornataIl = LocalDateTime.now();
    }

    /**
     * PASSO 8.7 — "sono arrivato almeno fin qui?".
     *
     * E' l'unica domanda su cui si decide una compensazione: si guarda cosa
     * e' GIA' SUCCESSO, non l'ordine delle chiamate e non quale eccezione e'
     * arrivata.
     */
    public boolean haRaggiunto(PassoSaga passo) {
        return passoRaggiunto.almeno(passo);
    }

    public void completata() {
        this.stato = StatoSaga.COMPLETATA;
        this.aggiornataIl = LocalDateTime.now();
    }

    /**
     * La saga e' fallita e ha compensato.
     *
     * Il booleano non e' un dettaglio: distingue "tutto rimesso a posto" da
     * "qualcosa e' rimasto storto", e la seconda e' l'unica delle due che
     * qualcuno dovra' guardare a mano. Vedi StatoSaga.COMPENSAZIONE_PARZIALE.
     */
    public void compensata(boolean completa, String errore) {
        this.stato = completa ? StatoSaga.COMPENSATA : StatoSaga.COMPENSAZIONE_PARZIALE;
        this.ultimoErrore = troncato(errore);
        this.aggiornataIl = LocalDateTime.now();
    }

    /**
     * La colonna e' VARCHAR(500) e i messaggi di errore non hanno un tetto:
     * uno stack trace incollato in un messaggio, o un corpo JSON riportato
     * per intero, supera i 500 caratteri senza sforzo. Senza questo taglio
     * la scrittura fallirebbe — e fallirebbe proprio mentre si sta cercando
     * di registrare che qualcosa e' gia' andato storto, che e' il momento
     * peggiore per perdere un'informazione.
     */
    private static String troncato(String errore) {
        if (errore == null) {
            return null;
        }
        return errore.length() <= 500 ? errore : errore.substring(0, 497) + "...";
    }
}
