# booking-service

Le prenotazioni, e il coordinamento dell'acquisto. Porta **8083**, database
**`booking_db`** (suo, non uno schema dentro `shows_db`).

È l'unico dei tre servizi che **chiama** gli altri, e questo cambia tutto ciò
che gli serve addosso: timeout espliciti, traduzione degli errori altrui e —
dal G7 — circuit breaker e retry.

---

## Il flusso di `POST /bookings` (passo 6.10)

```
1. GET  shows-service   /shows/{id}        prezzo base, orario, titolo
2. POST pricing-service /prices/quote      prezzo unitario
3. POST shows-service   /shows/{id}/reserve   i posti vengono scalati
4. INSERT su booking_db                     -> 201 Created
```

**L'ordine è la regola, non un dettaglio.** Riservare prima di conoscere il
prezzo significherebbe tenere occupati dei posti per un acquisto che potrebbe
non concludersi. Tutto ciò che può fallire senza conseguenze, fallisce prima.

Il passo 3 è il primo **irreversibile**: da lì in poi qualcosa è cambiato in un
altro servizio.

### Il buco del G6, che è in vista di proposito

Se il passo 4 fallisce — il database è pieno, la connessione cade, il processo
viene ucciso in mezzo — **i posti restano riservati**. Il cliente riceve un
errore e non ha nessuna prenotazione; il cinema ha due poltrone in meno da
vendere, per sempre, e nessuno lo sa.

Non si risolve con una transazione più grande: una transazione locale non può
annullare un POST HTTP già arrivato a destinazione. Il rollback distribuito
esiste (XA, two-phase commit) e nei microservizi non si usa, perché tiene
bloccate le risorse di tutti i partecipanti per tutta la durata
dell'operazione.

La soluzione è la **saga**, ed è il G8. `ShowsClient.rilascia()` è già scritto
e oggi non lo chiama nessuno. C'è perfino un test che lo afferma:

```java
@DisplayName("G6, il buco noto: se il salvataggio fallisce i posti restano riservati")
```

Al G8 quella riga diventerà `verify(showsClient).rilascia(...)` e il test
racconterà la storia del cambiamento.

---

## Il `sagaId`

Lo generiamo noi — siamo chi coordina — e lo mandiamo **identico** a ogni
servizio coinvolto. Oggi serve a una cosa sola: ricucire i log di tre processi
diversi.

```bash
curl -s -X POST localhost:8083/bookings -H 'Content-Type: application/json' \
     -d '{"showId":1,"customerType":"STUDENT","quantity":2}'
# -> { "sagaId": "3f2a1b9c-...", ... }

docker compose logs | grep 3f2a1b9c-
```

Dal G8 diventa la chiave dell'**idempotenza**: il vincolo `UNIQUE` su
`saga_id` è già nella `V1`, e c'è già un test che lo fa scattare. Un vincolo
che non si è mai visto fallire è un vincolo di cui non si sa se funziona.

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

## La traduzione degli errori (passo 6.9)

| shows / pricing rispondono | diventa | perché |
|---|---|---|
| `404` | **404** | l'utente ha chiesto uno spettacolo che non c'è: errore suo, non guasto nostro |
| `409` | **409** | la richiesta era scritta bene, è lo *stato* a renderla impossibile |
| `5xx` o timeout | **503** + `Retry-After` | il nostro codice ha funzionato, è un altro a non esserci |
| altri `4xx` | **500** | **siamo noi** ad aver mandato una richiesta sbagliata |

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
docker compose up -d          # avvia solo booking-db sulla 5433
./mvnw spring-boot:run
```

I default di `application.yaml` puntano a `localhost:8081` e `localhost:8082`:
servono `shows-service` e `pricing-service` accesi perché `POST /bookings`
funzioni.

### Il sistema completo

```bash
cd ../cinema-deploy && docker compose up --build
```

Poi `http://localhost:8083/swagger-ui.html`, oppure
[`http/bookings.http`](http/bookings.http).

---

## I test

```bash
./mvnw test      # veloce, non pretende Docker
./mvnw verify    # aggiunge l'IT con PostgreSQL vero (Testcontainers)
```

| File | Domanda a cui risponde |
|---|---|
| `BookingServiceTest` | la **coreografia**: quali passi, in quale ordine, cosa succede quando uno fallisce. Gateway mockati, niente rete |
| `BookingControllerTest` | la **tabella di traduzione** del passo 6.9, vista in HTTP |
| `BookingRepositoryIT` | la `V1` e l'entità dicono la stessa cosa |

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
di `shows-service` e `pricing-service`.

Sembrerebbe sensato («senza di loro non so prenotare») ed è un errore grave: un
guasto a valle toglierebbe dal bilanciatore anche le nostre istanze sane, e
`GET /bookings` — che non chiama nessuno — smetterebbe di rispondere insieme a
`POST /bookings`. I guasti a valle si gestiscono con i 503 del passo 6.9 e con
il circuit breaker del G7, **non spegnendosi**.

---

## Stack

Spring Boot 4.1.1 · Java 21 · RestClient (`spring-boot-starter-restclient`,
obbligatorio in Boot 4) · PostgreSQL 17 · Flyway · springdoc-openapi · Lombok ·
Testcontainers
