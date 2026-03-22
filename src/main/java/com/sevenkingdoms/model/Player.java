package com.sevenkingdoms.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class Player {

    private String id;           // UUID univoco del giocatore
    private String name;         // Nome visualizzato
    private String color;        // Colore cubetti: "red", "blue", "green", "yellow"
    private int colorIndex;      // Indice 0-3

    private int cubesInReserve;  // Cubetti rimasti nella riserva (max 20)
    private int papalFavors;     // Favori del Papa in possesso
    private boolean hasFirstPlayerToken; // Ha il segnalino primo giocatore
    private boolean actionDone;  // Ha già fatto l'azione in questo turno

    private int marketCubes;     // Cubetti nel mercato
    private int bishops;         // Vescovi piazzati nelle cattedrali

    // Punteggio calcolato a fine partita
    private int victoryPoints;
    private boolean bot;              // true se è un giocatore automatico

    public static Player create(String id, String name, String color, int colorIndex) {
        return createPlayer(id, name, color, colorIndex, false);
    }

    public static Player createBot(String id, String name, String color, int colorIndex) {
        return createPlayer(id, name, color, colorIndex, true);
    }

    private static Player createPlayer(String id, String name, String color,
                                        int colorIndex, boolean isBot) {
        return Player.builder()
                .id(id)
                .name(name)
                .color(color)
                .colorIndex(colorIndex)
                .cubesInReserve(20)
                .papalFavors(0)
                .hasFirstPlayerToken(false)
                .actionDone(false)
                .marketCubes(0)
                .bishops(0)
                .victoryPoints(0)
                .bot(isBot)
                .build();
    }

    public boolean hasPapalFavor() {
        return papalFavors > 0;
    }

    public void spendPapalFavor() {
        if (papalFavors <= 0) throw new IllegalStateException("Nessun Favore Papale disponibile");
        papalFavors--;
    }

    public void gainPapalFavor() {
        papalFavors++;
    }

    public boolean takeCubeFromReserve() {
        if (cubesInReserve <= 0) return false;
        cubesInReserve--;
        return true;
    }

    public void returnCubeToReserve() {
        cubesInReserve++;
    }
}