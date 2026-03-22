package com.sevenkingdoms.model;

import com.sevenkingdoms.model.enums.CharacterType;
import com.sevenkingdoms.model.enums.GamePhase;
import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import com.sevenkingdoms.model.VictoryBreakdown;
import java.util.Map;
import java.util.Optional;

@Data
@Builder
public class GameState {

    private String gameId;                      // ID univoco della partita
    private GamePhase phase;                     // Fase corrente del turno
    private List<Player> players;               // Lista giocatori (ordine di turno)
    private List<Province> provinces;           // Le 7 province
    private Map<CharacterType, GameCharacter> characters; // I 6 personaggi

    private int currentPlayerIndex;             // Chi deve agire ora
    private int firstPlayerIndex;               // Chi ha il segnalino primo giocatore
    private int turnNumber;                     // Numero del turno corrente

    private int invasionCubesRemaining;         // Cubetti invasione rimasti (max 30)
    private int religiousMarkersRemaining;       // Segnalini influenza religiosa rimasti (max 9)

    // Mercato: playerIndex → numero di cubetti
    private List<Integer> marketCubes;          // indice = playerIndex, valore = n. cubetti

    // Azioni disponibili nel turno (quale personaggio è ancora disponibile)
    private List<CharacterType> availableActions;

    // Fase passiva: stato intermedio per scelta eco Mercante
    private String passiveSubStep;               // null | "MERCANTE_CHOOSE"
    private int pendingMercantePlayerIndex;      // chi deve scegliere (-1 se nessuno)
    private List<Integer> pendingMercanteEcoTargets; // province disponibili

    // Log degli eventi del turno (per mostrare al frontend)
    private List<String> eventLog;

    // Dettaglio punti vittoria (popolato a fine partita)
    private List<VictoryBreakdown> victoryBreakdowns;

    // ──────────────────────────────────────────────
    // Factory: crea una nuova partita
    // ──────────────────────────────────────────────

    public static GameState newGame(String gameId, List<Player> players) {
        GameState state = GameState.builder()
                .gameId(gameId)
                .phase(GamePhase.SETUP)
                .players(players)
                .provinces(buildProvinces())
                .characters(buildCharacters())
                .currentPlayerIndex(0)
                .firstPlayerIndex(0) // Il giocatore 1 è il primo (ultimo prende Favore Papale)
                .turnNumber(1)
                .invasionCubesRemaining(30)
                .religiousMarkersRemaining(9)
                .marketCubes(new ArrayList<>(List.of(0, 0, 0, 0)))
                .availableActions(new ArrayList<>(List.of(CharacterType.values())))
                .passiveSubStep(null)
                .pendingMercantePlayerIndex(-1)
                .pendingMercanteEcoTargets(new ArrayList<>())
                .eventLog(new ArrayList<>())
                .victoryBreakdowns(new ArrayList<>())
                .build();

        // Ultimo giocatore prende un Favore Papale
        players.get(players.size() - 1).gainPapalFavor();
        // Primo giocatore prende il segnalino
        players.get(0).setHasFirstPlayerToken(true);

        return state;
    }

    // ──────────────────────────────────────────────
    // Province: 0=Nordheim(7) 1=Westmarch(6) 2=Easthaven(8)
    //           3=Capitale(9) 4=Southveil(8) 5=Dunholt(7) 6=Riverfen(6)
    //
    // Adiacenze via terra:
    //   Nordheim(0):  Westmarch(1), Easthaven(2)           [mare: Riverfen(6)]
    //   Westmarch(1): Nordheim(0), Easthaven(2), Capitale(3) [mare: Dunholt(5)]
    //   Easthaven(2): Nordheim(0), Westmarch(1), Capitale(3), Southveil(4)
    //   Capitale(3):  Westmarch(1), Easthaven(2), Southveil(4), Dunholt(5)
    //   Southveil(4): Easthaven(2), Capitale(3), Dunholt(5), Riverfen(6)
    //   Dunholt(5):   Capitale(3), Southveil(4), Riverfen(6)  [mare: Westmarch(1)]
    //   Riverfen(6):  Southveil(4), Dunholt(5)               [mare: Nordheim(0)]
    //
    // Posizioni iniziali personaggi:
    //   Re→Capitale(3), Papa→Capitale(3), Mercante→Nordheim(0)
    //   Spia→Easthaven(2), Fanteria→Westmarch(1), Cavalleria→Southveil(4)
    // ──────────────────────────────────────────────

    private static List<Province> buildProvinces() {
        return List.of(
            Province.create(0, "Nordheim",  false, List.of(1, 2),       List.of(6),    2, 7),
            Province.create(1, "Westmarch", false, List.of(0, 2, 3),    List.of(5),    2, 6),
            Province.create(2, "Easthaven", false, List.of(0, 1, 3, 4), List.of(),     2, 8),
            Province.create(3, "Capitale",  true,  List.of(1, 2, 4, 5), List.of(),     3, 9),
            Province.create(4, "Southveil", false, List.of(2, 3, 5, 6), List.of(),     2, 8),
            Province.create(5, "Dunholt",   false, List.of(3, 4, 6),    List.of(1),    2, 7),
            Province.create(6, "Riverfen",  false, List.of(4, 5),       List.of(0),    2, 6)
        );
    }


    private static Map<CharacterType, GameCharacter> buildCharacters() {
        return Map.of(
                CharacterType.RE,         GameCharacter.create(CharacterType.RE,         3),
                CharacterType.PAPA,       GameCharacter.create(CharacterType.PAPA,       3),
                CharacterType.MERCANTE,   GameCharacter.create(CharacterType.MERCANTE,   4),
                CharacterType.SPIA,       GameCharacter.create(CharacterType.SPIA,       2),
                CharacterType.FANTERIA,   GameCharacter.create(CharacterType.FANTERIA,   0),
                CharacterType.CAVALLERIA, GameCharacter.create(CharacterType.CAVALLERIA, 6)
        );
    }

    // ──────────────────────────────────────────────
    // Helper methods
    // ──────────────────────────────────────────────

    public void refreshAllProvinceStatuses() {
        provinces.forEach(p -> p.refreshStatus());
    }

    public Province getProvince(int id) {
        return provinces.get(id);
    }

    public Player getCurrentPlayer() {
        return players.get(currentPlayerIndex);
    }

    public Player getFirstPlayer() {
        return players.get(firstPlayerIndex);
    }

    public GameCharacter getCharacter(CharacterType type) {
        return characters.get(type);
    }

    public Optional<Player> getPlayerById(String playerId) {
        return players.stream().filter(p -> p.getId().equals(playerId)).findFirst();
    }

    public void addEvent(String message) {
        eventLog.add("[T" + turnNumber + "] " + message);
    }

    public int countWars() {
        return (int) provinces.stream().filter(Province::isAtWar).count();
    }

    public int countPacified() {
        return (int) provinces.stream().filter(Province::isPacified).count();
    }

    public boolean isGameOver() {
        // 3 guerre contemporanee
        if (countWars() >= 3) return true;
        // 3 province pacificate
        if (countPacified() >= 3) return true;
        // Provincia con traccia politica piena di invasione
        if (provinces.stream().anyMatch(Province::isFullOfInvasion)) return true;
        // Provincia con traccia economica piena di segnalini religiosi
        if (provinces.stream().anyMatch(Province::isEconomicFullOfReligious)) return true;
        // Nessun segnalino influenza religiosa rimasto
        if (religiousMarkersRemaining <= 0) return true;
        return false;
    }

    public void advanceToNextPlayer() {
        currentPlayerIndex = (currentPlayerIndex + 1) % players.size();
    }

    public boolean allPlayersActed() {
        return players.stream().allMatch(Player::isActionDone);
    }

    public void resetForNewTurn() {
        turnNumber++;
        players.forEach(p -> p.setActionDone(false));
        characters.values().forEach(GameCharacter::resetForNewTurn);
        availableActions = new ArrayList<>(List.of(CharacterType.values()));
        currentPlayerIndex = firstPlayerIndex;
        eventLog.clear();
    }

    public void addMarketCube(int playerIndex) {
        marketCubes.set(playerIndex, marketCubes.get(playerIndex) + 1);
    }
}