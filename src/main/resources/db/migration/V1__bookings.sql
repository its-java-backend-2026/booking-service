-- PASSO 6.2 — lo schema iniziale di booking_db.
--
-- REGOLA (dal passo 2.4, e vale anche qui): una migrazione gia' applicata
-- NON SI MODIFICA MAI. Flyway ne registra il checksum in
-- flyway_schema_history; cambiarne anche solo uno spazio fa fallire l'avvio
-- successivo con "Migration checksum mismatch". Se serve un cambiamento, se
-- ne scrive un'altra (V2, V3, ...).
--
-- ===========================================================================
-- QUESTO E' UN DATABASE SEPARATO, NON UNO SCHEMA DENTRO shows_db.
--
-- E' la decisione strutturale del G6, ed e' quella che rende veri tutti i
-- discorsi sull'indipendenza: due database diversi non possono avere una
-- JOIN, quindi nessuno puo' scrivere per sbaglio una query che lega le
-- prenotazioni agli spettacoli, e nessuno puo' rendersi dipendente dalla
-- NOSTRA struttura senza passare dalla nostra API.
--
-- Il prezzo si vede subito qui sotto: non c'e' nessuna FOREIGN KEY verso
-- shows. Il database non puo' piu' impedirci di prenotare uno spettacolo
-- inesistente. A verificarlo e' il passo 1 della saga (GET /shows/{id}), e
-- se in mezzo lo spettacolo viene cancellato resta una riga orfana.
-- L'integrita' referenziale fra servizi non esiste: al suo posto ci sono la
-- saga (G8) e la consapevolezza che i dati sono coerenti "alla fine", non
-- "sempre".
-- ===========================================================================

CREATE TABLE bookings (
    id            BIGSERIAL     PRIMARY KEY,

    -- PASSO 6.4 — l'identificativo dell'intera operazione di acquisto.
    -- UNIQUE: dal G8 e' questo vincolo a rendere la saga idempotente. Se due
    -- tentativi della stessa saga arrivano insieme, il database ne fa passare
    -- uno solo — e lo fa anche quando i due tentativi sono su due istanze
    -- diverse del servizio, dove un controllo in Java non basterebbe.
    saga_id       VARCHAR(64)   NOT NULL,

    -- L'id dello spettacolo NELL'ALTRO servizio. Niente REFERENCES: quella
    -- tabella sta in un altro database. Vedi il commento qui sopra.
    show_id       BIGINT        NOT NULL,

    -- VARCHAR e non un intero: l'entita' usa @Enumerated(EnumType.STRING),
    -- e nel database c'e' scritto "STUDENT". Con l'ORDINAL di default di JPA
    -- ci sarebbe un 1, e riordinare l'enum trasformerebbe gli studenti in
    -- senior senza che niente se ne accorga.
    customer_type VARCHAR(20)   NOT NULL,

    quantity      INT           NOT NULL CHECK (quantity > 0),

    -- ---- PASSO 6.3: i fatti copiati al momento dell'acquisto ----
    -- Non sono una cache e non si aggiornano mai: il biglietto dice quanto
    -- hai pagato ieri, non quanto costa oggi.
    movie_title   VARCHAR(200)  NOT NULL,
    start_time    TIMESTAMP     NOT NULL,
    unit_price    NUMERIC(8, 2) NOT NULL CHECK (unit_price >= 0),
    total_price   NUMERIC(10, 2) NOT NULL CHECK (total_price >= 0),

    created_at    TIMESTAMP     NOT NULL,

    CONSTRAINT uk_bookings_saga_id UNIQUE (saga_id)
);

-- I vincoli CHECK stanno nel database e non solo in Java: il database e'
-- l'ultima linea di difesa, e regge anche quando i dati arrivano da uno
-- script, da un'altra istanza del servizio o da un collega con psql aperto.

-- A supporto della domanda "quante prenotazioni ha questo spettacolo?", che
-- e' l'unico modo che abbiamo per riconciliare i nostri numeri con quelli di
-- shows-service quando qualcosa non torna.
CREATE INDEX idx_bookings_show_id ON bookings (show_id);
