package it.its.cinema.bookingservice.service;

import java.util.function.Supplier;

import it.its.cinema.bookingservice.client.LoyaltyClient;
import it.its.cinema.bookingservice.client.PaymentClient;
import it.its.cinema.bookingservice.client.ShowsClient;
import it.its.cinema.bookingservice.domain.Booking;
import it.its.cinema.bookingservice.domain.PassoSaga;
import it.its.cinema.bookingservice.domain.SagaState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * PASSI 8.5 e 8.7 — L'ORCHESTRATORE.
 *
 * ===========================================================================
 * TUTTA LA GIORNATA STA IN VENTI RIGHE, ED E' IL PUNTO.
 *
 * Il metodo esegui() qui sotto si legge dall'alto in basso come una lista
 * della spesa: riserva, paga, accredita, conferma. E' esattamente cio' che
 * si vuole da un'orchestrazione — il flusso di un acquisto sta scritto IN UN
 * POSTO SOLO, e chi arriva domani lo capisce leggendo un metodo.
 *
 * E' anche il motivo per cui il corso implementa la saga ORCHESTRATA e non
 * quella coreografata (passo 8.9): con gli eventi, questa sequenza non sta
 * scritta da nessuna parte. Sta distribuita in cinque servizi che reagiscono
 * l'uno all'altro, e per ricostruirla bisogna aprirli tutti e cinque. In
 * cambio si ottiene disaccoppiamento vero — nessuno conosce nessuno — e un
 * broker da installare, da sorvegliare e da spiegare.
 *
 * In cinque giornate, e con un flusso che deve restare leggibile, si sceglie
 * l'orchestrata. Il prezzo e' scritto qui sotto: questa classe conosce tutti
 * e tre i partecipanti, e ogni passo nuovo passa da qui.
 * ===========================================================================
 *
 * ===========================================================================
 * QUESTA CLASSE NON SCRIVE SUL DATABASE, E NON E' @Transactional.
 *
 * Tutte le scritture locali passano da SagaStore, che e' un bean diverso.
 * Non e' eleganza: e' il passo 8.6, e sono due regole che se violate non
 * danno nessun errore — una transazione che non si apre e una che resta
 * aperta per nove secondi. Il perche' per esteso sta nel commento di
 * SagaStore.
 * ===========================================================================
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BookingSaga {

    private final ShowsClient showsClient;
    private final PaymentClient paymentClient;
    private final LoyaltyClient loyaltyClient;
    private final SagaStore store;

    /**
     * PASSO 8.5 — I TRE PASSI, E IL catch CHE LI RIMETTE A POSTO.
     *
     * Ogni passo e' fatto di DUE righe, sempre nello stesso ordine:
     *
     *     la chiamata al servizio        (cambia qualcosa fuori)
     *     store.avanza(...)              (lo scrive qui)
     *
     * L'ordine non e' indifferente. Prima si fa, poi si registra: cosi', se
     * il processo muore in mezzo, lo stato scritto dice MENO di quello che e'
     * successo. Nel dubbio la compensazione fara' un passo in meno — un
     * guaio che si vede (dei posti bloccati) — invece che uno di troppo, che
     * sarebbe un rimborso mai dovuto.
     *
     * Il catch prende RuntimeException e non Exception: qui non c'e' niente
     * di checked, e catturare Error significherebbe provare a compensare con
     * la memoria finita.
     *
     * E L'ECCEZIONE VIENE RILANCIATA. La saga ha rimesso le cose a posto, non
     * ha fatto la prenotazione: chi ha chiamato deve ricevere il 402 o il 503
     * che gli spetta. Ingoiarla qui restituirebbe un 201 per un acquisto che
     * non e' avvenuto — ed e' il difetto piu' grave che questo metodo possa
     * avere.
     */
    public Booking esegui(Booking prenotazione, SagaState statoIniziale) {

        String sagaId = prenotazione.getSagaId();
        SagaState saga = statoIniziale;

        try {
            // --- 1. i posti ---
            // Il primo passo IRREVERSIBILE: da qui in poi qualcosa e'
            // cambiato in un altro servizio, e c'e' qualcosa da compensare.
            showsClient.riserva(prenotazione.getShowId(), prenotazione.getQuantity(), sagaId);
            saga = store.avanza(saga, PassoSaga.POSTI_RISERVATI);

            // --- 2. il pagamento ---
            // E' il passo che puo' dire di NO. Un 402 qui non e' un guasto:
            // e' la risposta che fa partire tutto il ritorno indietro, ed e'
            // il fallimento del passo 8.8.
            paymentClient.autorizza(sagaId, prenotazione.getId(), prenotazione.getTotalPrice());
            saga = store.avanza(saga, PassoSaga.PAGATO);

            // --- 3. i punti ---
            // Solo se ce ne sono: un biglietto omaggio vale zero punti, e
            // chiedere a loyalty-service di accreditarne zero sarebbe una
            // chiamata di rete per non fare niente (e un 400, perche' i
            // punti devono essere positivi). La saga resta a PAGATO, che e'
            // la verita': i punti non sono stati accreditati, e infatti non
            // andranno stornati.
            if (saga.getLoyaltyPoints() > 0) {
                loyaltyClient.accredita(sagaId, saga.getCustomerId(), saga.getLoyaltyPoints());
                saga = store.avanza(saga, PassoSaga.PUNTI_ACCREDITATI);
            }

            Booking confermata = store.conferma(prenotazione, saga);
            log.info("[saga {}] COMPLETATA: prenotazione {} confermata, {} posti, totale {}",
                    sagaId, confermata.getId(), confermata.getQuantity(),
                    confermata.getTotalPrice());
            return confermata;

        } catch (RuntimeException e) {
            log.warn("[saga {}] FALLITA al passo {}: {}. Comincio a compensare",
                    sagaId, saga.getPassoRaggiunto(), e.toString());

            compensa(sagaId, saga, e);
            store.fallisci(prenotazione);
            throw e;
        }
    }
    /**
     * PASSO 8.7 — LA COMPENSAZIONE, E LE TRE COSE CHE LA RENDONO CORRETTA.
     *
     * 1. GUARDA LO STATO RAGGIUNTO, NON L'ECCEZIONE.
     *
     *    Non c'e' nessun if sul tipo di errore, ed e' voluto. L'eccezione
     *    dice cosa e' andato storto; solo il passo raggiunto dice cosa era
     *    gia' stato fatto — e sono due cose diverse. Un timeout sul
     *    pagamento, per esempio, puo' arrivare dopo che l'addebito e' stato
     *    registrato: e' lo stato a saperlo, non l'eccezione.
     *
     * 2. VA ALL'INDIETRO.
     *
     *    Punti, pagamento, posti: l'ordine inverso a quello dell'andata. Non
     *    e' simmetria estetica — e' che si disfa per ultimo cio' che si e'
     *    fatto per primo, come si toglierebbe una pila di scatole. I posti
     *    si rilasciano ALLA FINE perche' sono la risorsa piu' contesa: se
     *    fossero i primi, la fila di clienti in attesa se li prenderebbe
     *    mentre noi stiamo ancora stornando un pagamento che potrebbe
     *    fallire.
     *
     * 3. UNA COMPENSAZIONE CHE FALLISCE NON FERMA LE ALTRE.
     *
     *    E' la riga piu' importante del passo 8.7. Se lo storno dei punti
     *    esplode e ci si ferma li', i posti restano bloccati per sempre —
     *    cioe' il guasto peggiore dei due, causato dal tentativo di
     *    sistemare il migliore. Ogni compensazione e' avvolta in prova(),
     *    che registra e va avanti; alla fine la saga sa se e' riuscito tutto.
     */
    private void compensa(String sagaId, SagaState saga, RuntimeException causa) {

        boolean tutteRiuscite = true;

        if (saga.haRaggiunto(PassoSaga.PUNTI_ACCREDITATI)) {
            // Il valore di ritorno di storna() conta: la compensazione dei
            // punti puo' riuscire SOLO IN PARTE (passo 8.2), e allora la
            // saga e' COMPENSAZIONE_PARZIALE anche se nessuno ha sollevato
            // niente. E' il caso piu' insidioso — non compare in nessun log
            // di errore, perche' non e' un errore.
            tutteRiuscite &= prova(sagaId, "storno dei punti",
                    () -> loyaltyClient.storna(sagaId, saga.getCustomerId(),
                            saga.getLoyaltyPoints()));
        }

        if (saga.haRaggiunto(PassoSaga.PAGATO)) {
            tutteRiuscite &= prova(sagaId, "storno del pagamento", () -> {
                paymentClient.storna(sagaId);
                return true;
            });
        }

        if (saga.haRaggiunto(PassoSaga.POSTI_RISERVATI)) {
            tutteRiuscite &= prova(sagaId, "rilascio dei posti", () -> {
                showsClient.rilascia(saga.getShowId(), saga.getQuantity(), sagaId);
                return true;
            });
        }

        store.compensata(saga, tutteRiuscite, causa.toString());

        if (tutteRiuscite) {
            log.info("[saga {}] COMPENSATA: il sistema e' tornato com'era", sagaId);
        } else {
            // ===============================================================
            // ERROR, e qui ci sta: e' l'unico caso di tutta la giornata in
            // cui resta qualcosa di storto che nessun automatismo
            // sistemera'. La riga in saga_state e' COMPENSAZIONE_PARZIALE, e
            // si trova con un WHERE — che e' tutta la differenza fra un
            // problema noto e un problema invisibile.
            // ===============================================================
            log.error("[saga {}] COMPENSAZIONE PARZIALE: qualcosa non e' tornato a posto, "
                    + "la riga in saga_state resta da guardare", sagaId);
        }
    }

    /**
     * Esegue una compensazione e NON lascia passare la sua eccezione.
     *
     * E' l'applicazione della regola 3 qui sopra. Il catch e' largo di
     * proposito — qualsiasi RuntimeException — perche' l'unica cosa che non
     * deve succedere e' che un guaio in una compensazione impedisca le altre.
     *
     * @return true se la compensazione e' riuscita per intero
     */
    private boolean prova(String sagaId, String cosa, Supplier<Boolean> azione) {
        try {
            boolean completa = Boolean.TRUE.equals(azione.get());
            if (completa) {
                log.info("[saga {}] compensato: {}", sagaId, cosa);
            } else {
                log.warn("[saga {}] compensazione '{}' riuscita solo in parte", sagaId, cosa);
            }
            return completa;
        } catch (RuntimeException e) {
            log.error("[saga {}] la compensazione '{}' e' FALLITA: {}. Proseguo con le altre",
                    sagaId, cosa, e.toString(), e);
            return false;
        }
    }
}
