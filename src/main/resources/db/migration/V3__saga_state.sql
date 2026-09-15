-- ===========================================================================
-- PASSO 8.4 — LO STATO DELLA SAGA, SCRITTO SU UNA TABELLA.
--
-- E' una V3 e non una V2 modificata: la regola del passo 2.4 non ha
-- eccezioni, una migrazione gia' applicata non si tocca. La V2 e' quella
-- della chiave di idempotenza del G7, ed e' gia' passata su tutti i database
-- che stanno girando.
--
-- ---------------------------------------------------------------------------
-- PERCHE' UNA SAGA DEVE LASCIARE UNA TRACCIA, E NON BASTA IL LOG.
--
-- Una saga e' una sequenza di passi su processi diversi, ognuno con la sua
-- compensazione. Finche' va tutto bene non serve ricordarsi niente. Il punto
-- e' cosa succede quando si interrompe A META':
--
--     i posti sono scalati, il pagamento e' passato, e il processo muore
--     prima di accreditare i punti.
--
-- Senza questa tabella, quella situazione e' INVISIBILE. Nel database delle
-- prenotazioni c'e' una riga a meta'; in shows-service dei posti scalati che
-- nessuno reclama; in payment-service un addebito senza biglietto. Nessuno
-- dei tre sa degli altri due, e nessuno puo' accorgersi che manca qualcosa.
--
-- Con la tabella, la stessa situazione e' una RIGA CHE SI PUO' CERCARE:
--
--     SELECT * FROM saga_state WHERE stato = 'IN_CORSO' AND aggiornata_il < now() - interval '5 minutes';
--
-- E' la query che un giorno diventera' un lavoro periodico che riprende le
-- saghe rimaste indietro. Oggi non c'e' ancora, e va bene: la differenza fra
-- un dato incoerente che si puo' trovare e uno che non si puo' trovare e'
-- gia' tutta qui.
--
-- Il log non basta, e vale la pena dire perche': i log si ruotano, si
-- perdono, non si interrogano con un WHERE e non dicono lo stato ATTUALE —
-- dicono cosa e' successo, uno per riga, e ricostruire da li' dove si e'
-- fermata una saga fra centomila e' un lavoro che nessuno fara'.
-- ---------------------------------------------------------------------------
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. LO STATO DELLA PRENOTAZIONE
--
-- Dal G8 una prenotazione non nasce piu' gia' confermata: nasce IN_CORSO,
-- e diventa CONFERMATA solo quando tutti i passi sono andati a buon fine.
-- Se la saga fallisce resta FALLITA, e la riga non sparisce.
--
-- CANCELLARLA SAREBBE PIU' SEMPLICE E SAREBBE SBAGLIATO: un cliente che
-- chiede "ho provato a comprare e non ha funzionato, cosa e' successo?"
-- merita una risposta, e una riga cancellata non ne ha nessuna.
-- ---------------------------------------------------------------------------

-- DEFAULT 'CONFERMATA' per le righe che ci sono gia': le prenotazioni del G6
-- e del G7 sono andate a buon fine, e dichiararle IN_CORSO sarebbe una
-- bugia che qualche lavoro periodico un giorno proverebbe a "riprendere".
ALTER TABLE bookings ADD COLUMN stato VARCHAR(20) NOT NULL DEFAULT 'CONFERMATA';

-- Il DEFAULT serviva a riempire il passato, non a coprire il futuro: da qui
-- in avanti lo stato lo dice sempre il codice, esplicitamente.
ALTER TABLE bookings ALTER COLUMN stato DROP DEFAULT;

-- ---------------------------------------------------------------------------
-- 2. LA SAGA
-- ---------------------------------------------------------------------------
CREATE TABLE saga_state (
    id              BIGSERIAL PRIMARY KEY,

    -- UNA saga per prenotazione. Il vincolo UNIQUE non e' cosmetico: e'
    -- l'unica cosa che impedisce a un difetto di aprire due coordinamenti
    -- sulla stessa prenotazione, che e' il modo piu' rapido di compensare
    -- due volte lo stesso acquisto.
    booking_id      BIGINT       NOT NULL,

    -- Chi sta comprando. Serve alla compensazione dei punti: per stornarli
    -- bisogna sapere a chi erano stati dati, e la prenotazione non lo dice.
    customer_id     VARCHAR(64)  NOT NULL,

    -- Copiati dalla prenotazione, e non e' una ridondanza inutile: sono i
    -- parametri con cui la compensazione dovra' ESSERE RIESEGUITA. Se un
    -- giorno un lavoro periodico riprendera' una saga interrotta, dovra'
    -- sapere quanti posti rilasciare e quanti punti stornare senza dover
    -- ricostruire niente.
    show_id         BIGINT       NOT NULL,
    quantity        INTEGER      NOT NULL,
    loyalty_points  INTEGER      NOT NULL,

    -- =====================================================================
    -- IL PASSO RAGGIUNTO, E NON "IL PROSSIMO DA FARE".
    --
    -- AVVIATA | POSTI_RISERVATI | PAGATO | PUNTI_ACCREDITATI
    --
    -- E' la differenza che fa funzionare la compensazione del passo 8.7:
    -- si guarda cosa e' GIA' SUCCESSO e si torna indietro da li'. Un
    -- "prossimo passo" descriverebbe un'intenzione, e le intenzioni non si
    -- compensano — si compensano i fatti.
    -- =====================================================================
    passo_raggiunto VARCHAR(30)  NOT NULL,

    -- IN_CORSO | COMPLETATA | COMPENSATA | COMPENSAZIONE_PARZIALE
    --
    -- L'ultimo e' il piu' importante dei quattro, ed e' quello che si
    -- dimentica sempre di prevedere: una compensazione puo' FALLIRE a sua
    -- volta, o riuscire solo in parte (i punti gia' spesi, passo 8.2).
    -- Senza uno stato che lo dica, quelle saghe risulterebbero sistemate.
    stato           VARCHAR(30)  NOT NULL,

    -- Perche' si e' fermata. In chiaro, perche' chi legge questa riga fra
    -- una settimana non avra' i log di quel momento.
    ultimo_errore   VARCHAR(500),

    aggiornata_il   TIMESTAMP    NOT NULL,

    CONSTRAINT uk_saga_state_booking UNIQUE (booking_id),

    CONSTRAINT fk_saga_state_booking FOREIGN KEY (booking_id) REFERENCES bookings (id),

    CONSTRAINT ck_saga_state_quantity_positiva CHECK (quantity > 0),
    CONSTRAINT ck_saga_state_punti_non_negativi CHECK (loyalty_points >= 0)
);

-- ===========================================================================
-- L'INDICE CHE SERVE A UNA QUERY CHE ANCORA NON ESISTE.
--
--     SELECT * FROM saga_state
--      WHERE stato = 'IN_CORSO' AND aggiornata_il < now() - interval '5 minutes';
--
-- E' la domanda "quali saghe sono rimaste per strada?", cioe' il lavoro
-- periodico che un domani le riprendera'. Costa una riga adesso, e senza di
-- lui quella query scandisce tutta la tabella — cioe' tutte le prenotazioni
-- mai fatte, per trovarne tre.
-- ===========================================================================
CREATE INDEX idx_saga_state_da_riprendere ON saga_state (stato, aggiornata_il);
