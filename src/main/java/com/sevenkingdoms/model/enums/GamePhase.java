package com.sevenkingdoms.model.enums;

public enum GamePhase {
    SETUP,              // Fase di preparazione / piazzamento iniziale
    PLAYER_ACTIONS,     // Fase 1: i giocatori scelgono e fanno le loro azioni
    PASSIVE_ACTIONS,    // Fase 2: risoluzione azioni passive dei personaggi
    END_TURN,           // Fase 3: fine turno (invasione, costo guerra, controllo fine gioco)
    GAME_OVER           // Partita terminata
}
