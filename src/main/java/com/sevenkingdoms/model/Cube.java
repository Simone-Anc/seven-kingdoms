package com.sevenkingdoms.model;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class Cube {

    public static final int EMPTY    = -2; // Slot vuoto
    public static final int INVASION = -1; // Cubetto invasione (nero)

    private int playerIndex = EMPTY;

    public Cube(int playerIndex) {
        this.playerIndex = playerIndex;
    }

    public boolean isEmpty()    { return playerIndex == EMPTY; }
    public boolean isInvasion() { return playerIndex == INVASION; }
    public boolean isColored()  { return playerIndex >= 0; }

    public static Cube empty()                   { return new Cube(EMPTY); }
    public static Cube invasion()                { return new Cube(INVASION); }
    public static Cube ofPlayer(int playerIndex) { return new Cube(playerIndex); }
}