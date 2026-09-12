package it.its.cinema.bookingservice.domain;

/**
 * PASSO 6.8 — LA STESSA PAROLA IN DUE SERVIZI, E DUE ENUM DIVERSI.
 *
 * pricing-service ha il suo CustomerType, con dentro le percentuali di
 * sconto. Questo non le ha e non le vuole: qui la categoria e' un FATTO da
 * registrare sulla prenotazione ("questo biglietto e' stato venduto a uno
 * studente"), la' e' un ingresso di una formula.
 *
 * La tentazione di estrarre un cinema-commons con dentro questo enum e' la
 * stessa del passo 6.8 sui DTO, e la risposta e' la stessa: il giorno in cui
 * pricing aggiunge la categoria MILITARE, con il jar condiviso dovremmo
 * rilasciare anche noi per compilare. Con due copie, booking continua a
 * funzionare e vende MILITARE il giorno in cui decide di accettarlo.
 *
 * Il prezzo della copia: se pricing rinomina un valore, ce ne accorgiamo
 * quando arriva un 400. Il prezzo del modulo condiviso: ce ne accorgiamo
 * quando non riusciamo piu' a rilasciare da soli.
 */
public enum CustomerType {
    STANDARD,
    STUDENT,
    SENIOR
}
