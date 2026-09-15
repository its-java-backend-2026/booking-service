# booking-service

Le prenotazioni, e il coordinamento dell'acquisto. Porta **8083**, database
**`booking_db`** (suo, non uno schema dentro `shows_db`).

È l'unico dei cinque servizi che **chiama** gli altri, e questo cambia tutto
ciò che gli serve addosso: timeout espliciti, traduzione degli errori altrui,
dal G7 circuit breaker, retry e idempotenza, e dal G8 il ruolo di
**orchestratore della saga** di acquisto.

---

## Il flusso di `POST /bookings` (passi 6.10 e 8.5)

```
0. SELECT per Idempotency-Key                se c'è già -> 200, e si finisce qui
1. GET  shows-service   /shows/{id}          prezzo base, orario, titolo
2. POST pricing-service /prices/quote        prezzo unitario
3. INSERT booking (IN_CORSO) + saga (AVVIATA)        <- SagaStore, una transazione
─── da qui comincia la saga, e ogni passo ha la sua compensazione ───
4. POST shows-service   /shows/{id}/reserve  -> passo POSTI_RISERVATI
5. POST payment-service /payments/authorize  -> passo PAGATO
6. POST loyalty-service /loyalty/{id}/credit -> passo PUNTI_ACCREDITATI
7. UPDATE booking (CONFERMATA) + saga (COMPLETATA)   -> 201 Created
```

Il passo 0 è del G7 (7.6) ed è il più economico di tutti: una query, e nessuna
chiamata HTTP avviene affatto.

**L'ordine è la regola, non un dettaglio.** Riservare prima di conoscere il
prezzo significherebbe tenere occupati dei posti per un acquisto che potrebbe
non concludersi. Tutto ciò che può fallire senza conseguenze, fallisce prima.

Il passo 4 è il primo **irreversibile**: da lì in poi qualcosa è cambiato in un
altro servizio, e c'è qualcosa da compensare.

### Il buco del G6 si chiude qui

Fino al G7 la riga si scriveva **alla fine**, dopo aver riservato i posti: se
la `INSERT` falliva, i posti restavano scalati per sempre e nessuno lo sapeva.
Il commento in cima a `BookingService` lo dichiarava da due giornate.

Dal G8 la riga si scrive **prima** (passo 3), e cambia due cose:

1. da quando i posti vengono scalati esiste già una riga che dice che qualcuno
   li ha presi e una saga che sa come rimetterli a posto;
2. la corsa sull'`Idempotency-Key` si risolve **prima** di aver chiamato
   chiunque: due doppi clic simultanei non arrivano nemmeno a riservare.

Non è un dettaglio di ordine: scrivere il fatto **prima** di agire fuori è
l'unico modo di sapere, dopo, che cosa si era cominciato.

---

## La saga (G8)

`BookingSaga` è venti righe e si legge come una lista della spesa. È il punto
dell'orchestrazione: il flusso di un acquisto sta scritto **in un posto solo**.

```java
try {
    shows.riserva(...);    store.avanza(saga, POSTI_RISERVATI);
    payment.autorizza(...); store.avanza(saga, PAGATO);
    loyalty.accredita(...); store.avanza(saga, PUNTI_ACCREDITATI);
    return store.conferma(prenotazione, saga);
} catch (RuntimeException e) {
    compensa(sagaId, saga, e);
    store.fallisci(prenotazione);
    throw e;                       // il 402 o il 503 arrivano al chiamante
}
```

Ogni passo è **prima la chiamata, poi la scrittura dello stato**: se il
processo muore in mezzo, lo stato dice *meno* di quello che è successo. Nel
dubbio si compensa un passo in meno — un guaio che si vede (posti bloccati) —
invece che uno di troppo, che sarebbe un rimborso mai dovuto.

### La compensazione (passo 8.7)

Tre regole, e sono tutte e tre visibili nel codice:

1. **guarda lo stato raggiunto, non l'eccezione.** Non c'è nessun `if` sul tipo
   di errore: l'eccezione dice cosa è andato storto, solo il passo raggiunto
   dice cosa era già stato fatto.
2. **va all'indietro** — punti, pagamento, posti. I posti si rilasciano per
   ultimi perché sono la risorsa più contesa: rilasciarli prima significherebbe
   darli via mentre si sta ancora stornando un pagamento che potrebbe fallire.
3. **una compensazione che fallisce non ferma le altre.** Fermarsi alla prima
   lascerebbe i posti bloccati per sempre: il guasto peggiore, causato dal
   tentativo di sistemare quello migliore.

### `saga_state` (passo 8.4)

Lo stato della saga sta su una riga, non in una variabile locale. Una variabile
funziona per tutta la durata della chiamata e smette di funzionare nell'unico
momento in cui servirebbe: quando il processo muore fra un passo e l'altro.

```sql
-- la query per cui esiste la tabella
SELECT * FROM saga_state
 WHERE stato = 'IN_CORSO' AND aggiornata_il < now() - interval '5 minutes';

-- e quelle che nessun automatismo sistemerà
SELECT * FROM saga_state WHERE stato = 'COMPENSAZIONE_PARZIALE';
```

`COMPENSAZIONE_PARZIALE` è lo stato che distingue un sistema distribuito
raccontato da uno costruito: una compensazione può **fallire** (loyalty non
risponde) o **riuscire solo in parte** (il cliente ha già speso i punti, passo
8.2). In entrambi i casi il sistema non è tornato com'era, e c'è una riga che
lo dice invece di far finta di niente.

### Le scritture stanno in una classe a parte (passo 8.6)

`SagaStore` esiste per due regole che, se violate, **non danno nessun errore**:

- `@Transactional` funziona solo attraverso il proxy di Spring: un metodo
  privato dell'orchestratore non aprirebbe nessuna transazione, in silenzio;
- una transazione non deve mai restare aperta durante una chiamata di rete —
  con i timeout del passo 6.6 e cinque servizi sarebbero fino a quindici
  secondi di connessione occupata per prenotazione.

---

## Il `sagaId`

Lo generiamo noi — siamo chi coordina — e lo mandiamo **identico** a ogni
servizio coinvolto. Oggi serve a una cosa sola: ricucire i log di tre processi
diversi.

```bash
curl -s -X POST localhost:8083/bookings -H 'Content-Type: application/json' \
     -H "Idempotency-Key: $(uuidgen)" \
     -d '{"showId":1,"customerType":"STUDENT","quantity":2}'
# -> { "sagaId": "3f2a1b9c-...", ... }

docker compose logs | grep 3f2a1b9c-
```

Dal G8 diventa la chiave dell'idempotenza **verso gli altri servizi**: il
vincolo `UNIQUE` su `saga_id` è già nella `V1`, e c'è già un test che lo fa
scattare. Un vincolo che non si è mai visto fallire è un vincolo di cui non si
sa se funziona.

Non va confuso con l'`Idempotency-Key` del G7 (7.6): quella la sceglie il
**client**, una per *intenzione*; il `sagaId` lo generiamo noi, uno per
*tentativo*.

Non si accetta dal client: è l'identità di un'operazione che coordiniamo noi,
e lasciarla scegliere a chi chiama significherebbe permettergli di riusare
quella di un altro.

---

## I dati copiati (passo 6.3)

`movieTitle`, `startTime` e `unitPrice` sono copiati al momento dell'acquisto
e **non si aggiornano mai più**.

> Il biglietto dice quanto hai pagato ieri, non quanto costa oggi.

Non è una cache da invalidare: è un **fatto storico**. Se domani il cinema alza
il prezzo, sposta lo spettacolo o corregge un refuso nel titolo, la riga non
cambia di una virgola.

La differenza è pratica: una cache richiederebbe un meccanismo per accorgersi
dei cambiamenti a monte (eventi, polling, scadenze), e tutto il sistema
diventerebbe più complicato. Essendo fatti, non serve niente — **la copia è il
punto, non il compromesso**.

Il vantaggio si vede subito: `GET /bookings/{id}` non chiama nessuno, e
funziona anche con `shows-service` spento.

---

## La resilienza (G7)

### Circuit breaker e retry non sono la stessa cosa

| | a cosa serve | cosa fa |
|---|---|---|
| **Retry** | il guasto **passeggero**: un pacchetto perso, un riavvio, una latenza momentanea | riprova, con backoff esponenziale e jitter |
| **Circuit breaker** | il guasto **persistente** | smette di chiamare |

Messi insieme si completano; messi male si sommano — senza breaker, il retry
**triplica** il carico su un servizio che sta già affogando.

### L'ordine in cui si annidano cambia il significato di tutti i numeri

È la riga che non si trova nei tutorial. Il default di Resilience4j è
`retry( breaker( chiamata ) )`: ogni **tentativo** è un campione del breaker,
quindi una prenotazione andata male ne vale tre e `minimumNumberOfCalls: 10`
non vuol dire dieci prenotazioni ma dieci tentativi di rete. E a circuito
aperto il retry ritenta comunque, aspettando 600ms per farsi dire tre volte
ciò che si sapeva già.

Qui l'ordine è invertito in `application.yaml` (`circuitBreakerAspectOrder` /
`retryAspectOrder`): **`breaker( retry( chiamata ) )`**. Una prenotazione è un
campione, ritentata o no, e a circuito aperto si risponde in microsecondi.

### Il retry sta solo dove ripetere è sicuro

```
perId()      GET, una lettura                 @CircuitBreaker + @Retry
riserva()    POST che SCALA dei posti         @CircuitBreaker + @Retry  (dal G8)
rilascia()   POST che RIMETTE dei posti       @CircuitBreaker + @Retry  (dal G8)
quote()      calcolo puro                     @CircuitBreaker + @Retry
accredita()  POST idempotente sul sagaId      @CircuitBreaker + @Retry  (G8)
storna()     POST idempotente sul sagaId      @CircuitBreaker + @Retry  (G8)
autorizza()  il pagamento                     @CircuitBreaker, e basta
refund()     lo storno del pagamento          @CircuitBreaker, e basta
```

La regola è l'**idempotenza**, non «è una GET». E il caso in cui il retry
scatta è proprio quello in cui il danno è più probabile: il **timeout**.
Timeout non vuol dire «non è arrivata», vuol dire «non so se è arrivata».

**Al G8 il retry su `riserva()` si è acceso**, come era scritto qui dal G7:
`shows-service` ha la sua `show_operations` con `UNIQUE (saga_id,
operation_type)`, quindi la seconda chiamata non fa niente. La regola non è
cambiata — si ritenta ciò che è idempotente — è cambiato il fatto che adesso lo
sia, e il lavoro per renderlo tale è stato fatto **dall'altra parte del filo**.

**Su `payment` non c'è retry, e non ci sarà** (passo 7.3). Tecnicamente sarebbe
sicuro (`UNIQUE` su `saga_id`), ma con i soldi il margine si tiene largo:
un'autorizzazione di cui non si conosce l'esito si riconcilia guardando, non
ritentando al buio. C'è un test che lo verifica.

Il **breaker** invece sta su tutti: non riesegue niente, si limita a non
tentare, e rifiutarsi di chiamare non ha mai effetti collaterali.

### Il fallback è onesto (passo 7.5)

Nessuno dei fallback inventa niente. La tentazione, quando `pricing` non
risponde, è «usiamo il prezzo base e andiamo avanti»: il sistema resterebbe in
piedi, nessuno vedrebbe un errore, e venderemmo biglietti all'importo
sbagliato. Lo scopriremmo settimane dopo, in contabilità.

> Un fallback che mente è peggio di un errore. L'errore lo vedono tutti
> subito; il dato sbagliato non lo vede nessuno, e resta.

Servono lo stesso, per due cose che senza di loro non ci sarebbero:

1. **il log della causa vera.** Il fallback cattura *qualsiasi* eccezione,
   quindi maschera ciò che è successo davvero: senza il `causa.toString()` un
   banale errore di deserializzazione sembrerebbe per sempre «il servizio è
   giù»;
2. **la traduzione di `CallNotPermittedException`.** A circuito aperto
   Resilience4j solleva un'eccezione sua, che `GestoreErrori` non conosce:
   senza fallback diventerebbe un **500**, cioè «abbiamo un bug» proprio
   mentre il sistema si sta difendendo come gli abbiamo chiesto.

Tutto il resto risale **intatto**: un 404 resta un 404 e un bug nostro resta
un 500. Un fallback che trasformasse tutto in 503 renderebbe invisibile ogni
errore di contratto dietro «è giù qualcun altro».

### Quali eccezioni contano come fallimento

È la parte che si dimentica sempre. Contano **solo** i servizi a valle che non
rispondono: un 404 (lo spettacolo non esiste) e un 409 (posti esauriti) sono
risposte *perfette* a domande sbagliate, e se contassero basterebbero dieci
utenti che cercano uno spettacolo cancellato per aprire il circuito e togliere
il servizio a tutti gli altri.

### Un breaker aperto non ci rende malati

`/actuator/health` resta **UP** con un circuito aperto, e
`/actuator/health/readiness` non guarda i breaker affatto. Un breaker aperto
racconta il guasto di *qualcun altro*: dichiararci DOWN ci farebbe togliere
dal bilanciatore o riavviare, e riavviarci non sistemerebbe il servizio a
valle — toglierebbe anche le parti di noi che funzionavano.

> **Trappola Boot 4.** `registerHealthIndicator: true` da solo non basta:
> l'indicatore di `resilience4j-spring-boot3` è compilato contro i package di
> Boot 3 e la sua auto-configurazione viene scartata *in silenzio*. Il breaker
> funziona lo stesso e si vede in `/actuator/circuitbreakers`; a rimetterlo in
> `/actuator/health` è `BreakerHealthIndicator`, venti righe scritte contro
> l'API nuova.

---

## L'idempotenza (passo 7.6)

`POST /bookings` pretende un header **`Idempotency-Key`**.

Il caso da cui difendersi è il più comune di tutti: l'utente preme «Paga», la
rete rallenta, la risposta non arriva, l'utente preme di nuovo. Senza
idempotenza sono due prenotazioni e due addebiti — e se ne accorge il cliente,
non noi.

### Non è il `sagaId`

| | chi la genera | una per |
|---|---|---|
| `Idempotency-Key` | il **client** | **intenzione** di acquisto |
| `sagaId` | **noi** | **tentativo** |

Se la chiave la generassimo noi non servirebbe a niente: ogni richiesta ne
avrebbe una nuova, e due richieste identiche resterebbero due prenotazioni. È
proprio perché è il client a ripetere la *stessa* stringa che possiamo
riconoscere il doppione.

L'header è **obbligatorio** e non lo generiamo quando manca: il servizio
funzionerebbe sempre e l'idempotenza non funzionerebbe mai. Meglio un 400 che
si nota il primo giorno.

### La difesa è in due tempi, e servono entrambi

**Prima** si cerca la chiave: se c'è, si restituisce la prenotazione di allora
senza chiamare nessuno.

**Dopo** si salva, e si accetta che il vincolo `UNIQUE` della `V2` possa dire
di no. Due richieste arrivate *insieme* superano tutte e due il controllo
applicativo — che non è sbagliato, è semplicemente fatto in un momento in cui
la risposta può ancora cambiare. Il vincolo invece decide nell'istante della
scrittura, e decide per **tutte le istanze** del servizio: un controllo in
Java vive dentro una JVM, il vincolo vive nell'unico posto che le istanze
condividono.

La violazione **non è un errore da propagare**: si rilegge la riga vincente e
si restituisce quella. Chi ha chiamato voleva una prenotazione, e ce l'ha.

### 200 e non 201, e soprattutto non 409

Il corpo è identico. Cambia l'affermazione: `201 Created` dice che *questa*
richiesta ha creato qualcosa, e ripetendola non è vero.

E non è un errore: un 409 insegnerebbe ai client a non ritentare mai, che è il
contrario di ciò per cui l'idempotenza esiste.

### Il buco che c'era, e che dal G8 non c'è più

Fino al G7 due richieste in corsa riservavano i posti **tutte e due** prima che
il vincolo ne fermasse una, e i posti del tentativo perdente restavano scalati.
Dal G8 la corsa si perde **prima** di chiamare chiunque, perché la `INSERT` è
il passo 3 e non il 7: non c'è niente da compensare perché non c'è niente da
disfare.

---

## La traduzione degli errori (passo 6.9)

| i servizi a valle rispondono | diventa | perché |
|---|---|---|
| `404` di shows | **404** | l'utente ha chiesto uno spettacolo che non c'è: errore suo, non guasto nostro |
| `409` di shows | **409** | la richiesta era scritta bene, è lo *stato* a renderla impossibile |
| `402` di payment | **402** | il pagamento ha funzionato, e la risposta è no. **La saga ha già compensato** |
| `5xx` o timeout | **503** + `Retry-After` | il nostro codice ha funzionato, è un altro a non esserci |
| altri `4xx` | **500** | **siamo noi** ad aver mandato una richiesta sbagliata |

Il `402` è l'esito nuovo del G8, e porta nel corpo un'informazione che al G7
non avremmo potuto dare: `"compensata": true`. È la sola cosa che distingue
*«non hai comprato»* da *«non hai comprato e ho lasciato due poltrone
bloccate»*. Non conta come fallimento per il circuit breaker (`ignoreExceptions`
in `application.yaml`): dieci carte rifiutate non sono un guasto, e contarle
toglierebbe la possibilità di pagare a tutti gli altri.

L'ultima riga è la più istruttiva. Un 400 da un servizio a valle significa che
il nostro JSON non gli piace: contratto disallineato, campo mancante, tipo
errato. È un **bug nostro**, e un bug nostro è un 500 con lo stack trace nei
log. Tradurlo in 503 lo nasconderebbe dietro *«è giù qualcun altro»* — e al G7
farebbe pure scattare retry e circuit breaker su un problema che riprovare non
risolverà mai.

**503 e non 500** è il cuore del passo:

- `500` dice «colpa nostra, un bug»: manda in caccia la persona sbagliata e
  dice al client che riprovare è inutile;
- `503` dice la verità: riprovare fra poco ha senso.

La traduzione avviene in **due tratti**. `ShowsClient` e `PricingClient`
trasformano i codici HTTP altrui in eccezioni di dominio; `GestoreErrori` le
ritrasforma in codici HTTP verso il *nostro* chiamante. Sembra un giro inutile
e non lo è: in mezzo, il service e il controller lavorano senza sapere niente
di HTTP.

---

## I timeout non sono tuning (passi 6.6 e 6.7)

```java
HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)   // vedi sotto
        .connectTimeout(Duration.ofSeconds(2))
        .build();
factory.setReadTimeout(Duration.ofSeconds(3));
```

Senza timeout espliciti l'`HttpClient` del JDK aspetta **per sempre**.

Un servizio **lento** fa più danni di un servizio **spento**: spento risponde
subito *connection refused* e ce ne liberiamo in millisecondi; lento tiene
occupato un nostro thread per tutta l'attesa, e con abbastanza richieste in
arrivo i thread finiscono tutti lì dentro — e **noi** smettiamo di rispondere,
anche alle richieste che con quel servizio non c'entrano niente.

Due timeout, due domande diverse: `connectTimeout` è «quanto aspetto che la
connessione si apra» (corto: o c'è o non c'è), `readTimeout` è «quanto aspetto
la risposta dopo aver chiesto» (più lungo: dall'altra parte c'è lavoro vero).

**HTTP/1.1 esplicito** (passo 6.7): l'`HttpClient` del JDK negozia HTTP/2 di
default, e con alcuni server la negoziazione fallisce con
`Received RST_STREAM: Protocol error` — un messaggio che non nomina né HTTP/2
né la negoziazione, e che manda a cercare un bug nel posto sbagliato. Succede
con WireMock (i test a contratto del G10) e con proxy datati. Fra i nostri
servizi HTTP/2 non porterebbe comunque nessun vantaggio: le chiamate sono
poche, piccole e in sequenza.

---

## Una cosa che qui NON c'è: `@Transactional` su `crea()`

Metterla significherebbe tenere aperta una transazione — e quindi una
connessione del pool — per tutta la durata di **tre chiamate HTTP**. Con i
timeout qui sopra sono fino a 9 secondi per prenotazione: bastano una decina di
richieste lente insieme per esaurire il pool, e a quel punto anche le `GET`
smettono di rispondere.

È la stessa lezione di `open-in-view: false` del G2, vista da un'altra
angolazione: *una transazione si apre il più tardi possibile, si chiude il
prima possibile, e mai intorno a un'attesa di rete.*

---

## I DTO di confine (passo 6.8)

`client/dto/` contiene **copie locali** dei contratti remoti: `ShowJson`,
`SeatsJson`, `QuoteRequestJson`, `QuoteResponseJson`. Non è un modulo condiviso
fra i servizi, e non deve diventarlo: un jar comune sembra far risparmiare
codice e in cambio impone di rilasciare tutti insieme — un database condiviso
travestito da dipendenza Maven.

Lo stesso vale per `CustomerType`, che esiste sia qui sia in `pricing-service`
con significati diversi: qui è un fatto da registrare, là è un ingresso di una
formula.

> Il prezzo della copia: se il fornitore rinomina un campo, ce ne accorgiamo
> quando arriva un `null`. Il prezzo del modulo condiviso: ce ne accorgiamo
> quando non riusciamo più a rilasciare da soli. Il primo si paga una volta.

Tutti i DTO in ingresso hanno `@JsonIgnoreProperties(ignoreUnknown = true)`:
senza, il giorno in cui `shows-service` aggiunge un campo — per lui
un'operazione compatibile — le nostre prenotazioni si fermerebbero.

---

## Avvio

### Solo il database, e il servizio dall'IDE

```bash
cp .env.example .env
docker compose up -d booking-db   # solo il database, sulla 5433
./mvnw spring-boot:run
```

I default di `application.yaml` puntano a `localhost:8081` e `localhost:8082`:
servono `shows-service` e `pricing-service` accesi perché `POST /bookings`
funzioni.

### Questo repository in container

```bash
docker compose up -d --build      # booking-db + booking-service sulla 8083
```

Costruisce l'immagine da questo `Dockerfile` e basta: gli altri due servizi
stanno negli altri repository e qui non ci sono. `CINEMA_SHOWS_URL` e
`CINEMA_PRICING_URL` puntano di default a `host.docker.internal`, così il
container li trova se li si sta facendo girare dall'IDE. Se non rispondono,
`POST /bookings` dà `503` e `GET /bookings` continua a funzionare.

### Il sistema completo

```bash
cd ../cinema-deploy && docker compose up --build
```

Poi `http://localhost:8083/swagger-ui.html`, oppure
[`http/bookings.http`](http/bookings.http).

### Provare il guasto a mano (passo 7.7)

```bash
cd ../cinema-deploy && docker compose stop pricing-service

for i in $(seq 1 12); do
  curl -s -o /dev/null -w "%{http_code} in %{time_total}s\n" \
       -X POST localhost:8083/bookings \
       -H "Content-Type: application/json" \
       -H "Idempotency-Key: $(uuidgen)" \
       -d '{"showId":1,"customerId":"mario.rossi","customerType":"STUDENT","quantity":2}'
done
```

La colonna dei tempi è la parte da guardare:

```
503 in 0.72s      <- i timeout del 6.6 più i due ritentativi del 7.3
...
503 in 0.62s      <- la decima: minimumNumberOfCalls
503 in 0.005s     <- il circuito è aperto, non stiamo più chiamando nessuno
503 in 0.004s
```

```bash
curl -s localhost:8083/actuator/health       # circuitBreakers.pricing.state: OPEN, status: UP
curl -s localhost:8083/actuator/circuitbreakers
curl -s localhost:8083/bookings/1            # continua a funzionare: 200 istantaneo
docker compose start pricing-service         # e riparte da solo, senza riavviare niente
```

### Provare il fallimento della saga (passo 8.8)

Il fallimento si provoca a comando: `payment-service` rifiuta sopra la soglia,
e 20 posti da 10.00 euro fanno 200.00.

```bash
cd ../cinema-deploy && docker compose up -d

# PRIMA: quanti posti ha lo spettacolo 1
curl -s localhost:8081/shows/1 | jq .availableSeats

curl -i -X POST localhost:8083/bookings \
     -H "Content-Type: application/json" \
     -H "Idempotency-Key: $(uuidgen)" \
     -d '{"showId":1,"customerId":"mario.rossi","customerType":"STUDENT","quantity":20}'
# -> 402 Payment Required, "compensata": true

# DOPO: gli stessi posti di prima. È la consegna del G8.
curl -s localhost:8081/shows/1 | jq .availableSeats
```

E la saga racconta dove si è fermata:

```bash
docker exec -it booking-db psql -U cinema -d booking_db \
  -c "SELECT id, booking_id, passo_raggiunto, stato, ultimo_errore
        FROM saga_state ORDER BY id DESC LIMIT 5;"
```

```
 passo_raggiunto  |   stato    |              ultimo_errore
-----------------+------------+------------------------------------------
 POSTI_RISERVATI | COMPENSATA | ...Pagamento rifiutato: Importo 200.00...
```

`POSTI_RISERVATI` e non `PAGATO`: la saga si è fermata **prima** del pagamento,
quindi la compensazione ha rilasciato i posti e non ha stornato niente
— non c'era niente da stornare.

---

## I test

```bash
./mvnw test      # veloce, non pretende Docker
./mvnw verify    # aggiunge l'IT con PostgreSQL vero (Testcontainers)
```

| File | Domanda a cui risponde |
|---|---|
| `BookingServiceTest` | l'idempotenza del 7.6 (corsa persa compresa) e l'apertura della saga: chi viene chiamato *prima* che la saga cominci |
| `BookingSagaTest` | le **decisioni** del G8: quali compensazioni, in quale ordine, e cosa succede quando una compensazione fallisce o riesce a metà. Client mockati, niente rete |
| `BookingControllerTest` | la **tabella di traduzione** del 6.9, il `402` del G8 e il contratto dell'`Idempotency-Key`, visti in HTTP |
| `BookingRepositoryIT` | la `V1`, la `V2` e l'entità dicono la stessa cosa |
| `SagaIT` | cosa **resta scritto** su `booking_db` quando è finita: `CONFERMATA`/`FALLITA`, `COMPLETATA`/`COMPENSATA`/`COMPENSAZIONE_PARZIALE`, e il passo a cui ci si è fermati |
| `ResilienzaIT` | il G7 è **acceso davvero**, e dal G8 che `riserva()` *viene* ritentata mentre il pagamento no |

`BookingSagaTest` e `SagaIT` si dividono il lavoro come si dividono il codice:
il primo prova le decisioni, il secondo cosa arriva sul database. Il secondo ha
bisogno di un container per una ragione precisa: una `@Transactional` che non
si apre — perché qualcuno ha spostato i metodi di `SagaStore` dentro
l'orchestratore — non dà nessun errore, e con i mock non si vedrebbe.

`ResilienzaIT` verifica il *cablaggio*, non Resilience4j. Tre cose che non
falliscono in compilazione: senza `resilience4j-spring-boot3` le annotazioni
sono decorazioni; con un nome di istanza sbagliato la configurazione del passo
7.2 non la legge nessuno; con un fallback dalla firma sbagliata arriva una
`NoSuchMethodException` la prima volta che un servizio va giù. Si scoprono
solo passandoci dentro.

Dell'`IT` la parte più utile è quella che non si vede: con `ddl-auto: validate`
il solo avvio del contesto confronta l'entità con lo schema creato da Flyway.
Un `@Column(length = 200)` contro un `VARCHAR(100)`, un campo aggiunto e
dimenticato in una `V2`: l'applicazione **non parte**, lì, invece che alle nove
di sera del primo rilascio.

---

## Nessuna foreign key verso `shows`

Nella `V1` `show_id` è un `BIGINT` senza `REFERENCES`: quella tabella sta in un
**altro database**, e i database non si parlano fra loro.

È la rinuncia più concreta del passaggio ai microservizi. Il database non può
più impedirci di prenotare uno spettacolo inesistente — a verificarlo è il
passo 1 della saga, e se in mezzo lo spettacolo viene cancellato resta una riga
orfana. L'integrità referenziale fra servizi non esiste: al suo posto ci sono
la saga e la consapevolezza che i dati sono coerenti *alla fine*, non *sempre*.

---

## Un dettaglio sulla readiness

`/actuator/health/readiness` di `booking-service` **non** dipende dalla salute
di `shows-service` e `pricing-service` — né dallo stato dei breaker del G7.

Sembrerebbe sensato («senza di loro non so prenotare») ed è un errore grave: un
guasto a valle toglierebbe dal bilanciatore anche le nostre istanze sane, e
`GET /bookings` — che non chiama nessuno — smetterebbe di rispondere insieme a
`POST /bookings`. I guasti a valle si gestiscono con i 503 del passo 6.9 e con
il circuit breaker del G7, **non spegnendosi**. Il gruppo `readiness` in
`application.yaml` è scritto esplicitamente per questo: dice quali indicatori
contano, e i breaker non sono fra quelli.

---

## Stack

Spring Boot 4.1.1 · Java 21 · RestClient (`spring-boot-starter-restclient`,
obbligatorio in Boot 4) · Resilience4j (Spring Cloud 2025.1.3) · PostgreSQL 17 ·
Flyway · springdoc-openapi · Lombok · Testcontainers
