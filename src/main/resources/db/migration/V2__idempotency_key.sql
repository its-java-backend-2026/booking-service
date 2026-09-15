-- PASSO 7.6 — LA CHIAVE DI IDEMPOTENZA, E PERCHE' E' UNA V2 E NON UNA V1
-- MODIFICATA.
--
-- La regola del passo 2.4 non ha eccezioni: una migrazione GIA' APPLICATA
-- non si tocca. Flyway ne ha registrato il checksum in flyway_schema_history,
-- e cambiare anche un solo spazio nella V1 fa fallire il prossimo avvio con
-- "Migration checksum mismatch" — su tutti gli ambienti in cui quella V1 e'
-- gia' passata, compreso quello di produzione.
--
-- Aggiungere una colonna e' quindi sempre un file nuovo. Costa una riga in
-- piu' nel repository e non costa niente a chi sta gia' girando.

-- ===========================================================================
-- PERCHE' SERVE UNA CHIAVE, VISTO CHE C'E' GIA' saga_id.
--
-- Sono due identita' diverse, ed e' la distinzione che fa funzionare la cosa:
--
--   saga_id          lo generiamo NOI, uno per TENTATIVO. Serve a ricucire i
--                    log di tre processi e, dal G8, a rendere idempotenti i
--                    passi verso gli altri servizi.
--
--   idempotency_key  la genera il CLIENT, una per INTENZIONE. "Compra questi
--                    due posti" e' una cosa sola anche se il telefono e' in
--                    ascensore e il pulsante viene premuto tre volte.
--
-- Se la chiave la generassimo noi non servirebbe a niente: ogni richiesta
-- che arriva ne avrebbe una nuova, e due richieste identiche resterebbero
-- due prenotazioni. E' proprio perche' e' il client a ripetere la STESSA
-- stringa che possiamo riconoscere il doppione.
-- ===========================================================================

-- In tre tempi, e non in un colpo solo con NOT NULL: se la tabella ha gia'
-- delle righe, una ADD COLUMN NOT NULL senza default le rifiuta tutte e la
-- migrazione fallisce a meta'.
ALTER TABLE bookings ADD COLUMN idempotency_key VARCHAR(64);

-- Le prenotazioni del G6 non avevano una chiave: si usa il loro saga_id, che
-- e' gia' unico per costruzione. Non e' un ripiego, e' la verita' storica —
-- per quelle righe il tentativo E' stato l'intenzione.
UPDATE bookings SET idempotency_key = saga_id WHERE idempotency_key IS NULL;

ALTER TABLE bookings ALTER COLUMN idempotency_key SET NOT NULL;

-- ===========================================================================
-- QUESTA RIGA E' LA PROTEZIONE VERA (passo 7.6).
--
-- Il controllo in Java ("l'ho gia' vista?") da solo NON BASTA, e il modo in
-- cui non basta e' istruttivo: due richieste con la stessa chiave che
-- arrivano insieme leggono entrambe "non c'e'", passano entrambe, e salvano
-- entrambe. Il controllo applicativo non e' sbagliato — e' semplicemente
-- fatto in un momento in cui la risposta puo' ancora cambiare.
--
-- Il vincolo UNIQUE invece decide nell'istante della scrittura, e decide per
-- TUTTE le istanze del servizio insieme: un controllo in Java vive dentro una
-- JVM, questo vive nell'unico posto che le tre istanze condividono.
-- ===========================================================================
ALTER TABLE bookings
    ADD CONSTRAINT uk_bookings_idempotency_key UNIQUE (idempotency_key);
