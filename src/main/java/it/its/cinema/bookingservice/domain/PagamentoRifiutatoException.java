package it.its.cinema.bookingservice.domain;

import lombok.Getter;

/**
 * PASSO 8.1 — IL 402 DI payment-service, VISTO DA QUI.
 *
 * E' la traduzione del rifiuto, e va tenuta ben distinta da
 * ServizioNonDisponibileException — sono due mondi opposti, e confonderli
 * romperebbe sia il G7 sia il G8:
 *
 *   ServizioNonDisponibile   il pagamento NON SI SA come sia andato. Si
 *                            ritenta, conta per il circuit breaker, ed e'
 *                            un 503 per il nostro chiamante.
 *
 *   PagamentoRifiutato       il pagamento e' andato benissimo e la risposta
 *                            e' NO. Non si ritenta (passo 7.3), NON conta
 *                            per il circuit breaker — dieci carte rifiutate
 *                            non sono un guasto — e per il nostro chiamante
 *                            e' un 402.
 *
 * E soprattutto: e' l'eccezione che fa partire la COMPENSAZIONE (passo 8.7).
 * E' il fallimento tipico della saga, quello del passo 8.8, ed e' anche il
 * piu' facile da provocare in aula — basta superare la soglia.
 */
@Getter
public class PagamentoRifiutatoException extends RuntimeException {

    private final String sagaId;

    public PagamentoRifiutatoException(String sagaId, String motivo) {
        super("Pagamento rifiutato: " + motivo);
        this.sagaId = sagaId;
    }
}
