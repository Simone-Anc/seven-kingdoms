package com.sevenkingdoms.service;

import com.sevenkingdoms.exception.GameException;
import com.sevenkingdoms.model.Cube;
import com.sevenkingdoms.model.GameState;
import com.sevenkingdoms.model.Player;
import com.sevenkingdoms.model.Province;
import com.sevenkingdoms.model.enums.GamePhase;
import com.sevenkingdoms.repository.GameRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class GameService {

    private final GameRepository gameRepository;
    private final NotificationService notificationService;

    // ── Crea partita ──────────────────────────────────────────────────────────

    public GameState createGame(List<String> playerNames, List<String> playerColors) {
        return createGame(playerNames, playerColors, null);
    }

    public GameState createGame(List<String> playerNames, List<String> playerColors,
                                 List<Boolean> botFlags) {
        if (playerNames.size() < 2 || playerNames.size() > 4) {
            throw GameException.invalidAction("Numero giocatori non valido (2-4)");
        }

        String gameId = UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        List<Player> players = new ArrayList<>();
        String[] colors = {"red", "blue", "green", "yellow"};
        for (int i = 0; i < playerNames.size(); i++) {
            boolean isBot = botFlags != null && i < botFlags.size()
                    && Boolean.TRUE.equals(botFlags.get(i));
            String color = playerColors != null ? playerColors.get(i) : colors[i];
            players.add(isBot
                    ? Player.createBot(UUID.randomUUID().toString(), playerNames.get(i), color, i)
                    : Player.create(UUID.randomUUID().toString(), playerNames.get(i), color, i));
        }

        GameState state = GameState.newGame(gameId, players);

        // Cubetto invasione dove parte la Spia (Easthaven=2)
        state.getProvince(2).addInvasionCube();

        // Segnalino religioso dove parte il Papa (Easthaven=2)
        state.getProvince(2).addReligiousMarker();

        state.addEvent("Partita creata! Turno di piazzamento iniziale.");
        log.info("Partita creata: {}", gameId);

        return gameRepository.save(state);
    }

    // ── Setup: piazzamento iniziale ───────────────────────────────────────────

    public GameState placeInitialCube(String gameId, String playerId, int provinceId) {
        GameState state = gameRepository.getOrThrow(gameId);

        validatePhase(state, GamePhase.SETUP);
        validateCurrentPlayer(state, playerId);

        Province province = state.getProvince(provinceId);
        if (province.isFull()) {
            throw GameException.invalidAction("Provincia piena: " + province.getName());
        }

        Player player = state.getCurrentPlayer();
        if (!player.takeCubeFromReserve()) {
            throw GameException.invalidAction("Nessun cubetto disponibile nella riserva");
        }

        province.addCube(Cube.ofPlayer(player.getColorIndex()));
        state.addEvent(player.getName() + " piazza un cubetto in " + province.getName());

        advanceSetup(state);

        GameState saved = gameRepository.save(state);
        notificationService.broadcastGameState(saved);
        return saved;
    }

    private void advanceSetup(GameState state) {
        int totalPlayers = state.getPlayers().size();
        int totalPlaced = state.getProvinces().stream()
                .mapToInt(p -> (int) p.getPoliticalTrack().stream()
                        .filter(Cube::isColored).count())
                .sum();

        if (totalPlaced >= totalPlayers * 3) {
            state.setPhase(GamePhase.PLAYER_ACTIONS);
            state.setCurrentPlayerIndex(state.getFirstPlayerIndex());
            state.addEvent("Setup completato! Inizia la partita.");
        } else {
            state.advanceToNextPlayer();
        }
    }

    // ── Validazioni ───────────────────────────────────────────────────────────

    public void validatePhase(GameState state, GamePhase expected) {
        if (state.getPhase() != expected) {
            throw GameException.wrongPhase(
                    "Fase errata: attesa " + expected + ", corrente " + state.getPhase());
        }
    }

    public void validateCurrentPlayer(GameState state, String playerId) {
        if (!state.getCurrentPlayer().getId().equals(playerId)) {
            throw GameException.notYourTurn("Non è il tuo turno");
        }
    }

    public void validatePlayerNotActed(GameState state, String playerId) {
        Player player = state.getPlayerById(playerId)
                .orElseThrow(() -> GameException.notFound("Giocatore non trovato"));
        if (player.isActionDone()) {
            throw GameException.invalidAction("Hai già eseguito un'azione in questo turno");
        }
    }

    public GameState getState(String gameId) {
        return gameRepository.getOrThrow(gameId);
    }

    // ── Unisciti a partita esistente ──────────────────────────────────────────
    // Usato in multiplayer: un giocatore si aggiunge con il suo nome e colore

    public GameState joinGame(String gameId, String playerName, String color) {
        GameState state = gameRepository.getOrThrow(gameId);

        if (state.getPhase() != com.sevenkingdoms.model.enums.GamePhase.SETUP) {
            throw GameException.invalidAction("Non puoi unirti: la partita è già iniziata");
        }

        // Trova il primo giocatore senza nome (slot libero)
        java.util.Optional<Player> slot = state.getPlayers().stream()
                .filter(p -> p.getName().startsWith("Bot") || p.getName().startsWith("Giocatore"))
                .filter(p -> p.isBot())
                .findFirst();

        if (slot.isEmpty()) {
            throw GameException.invalidAction("Tutti i posti sono già occupati");
        }

        Player player = slot.get();
        player.setName(playerName);
        player.setBot(false);

        state.addEvent(playerName + " si è unito alla partita!");

        GameState saved = gameRepository.save(state);
        notificationService.broadcastGameState(saved);
        return saved;
    }

    // ── Lobby ─────────────────────────────────────────────────────────────────

    public List<com.sevenkingdoms.api.GameController.LobbyEntry> getLobbyEntries() {
        return gameRepository.findAll().stream()
            .filter(state -> state.getPhase() == com.sevenkingdoms.model.enums.GamePhase.SETUP)
            .filter(state -> state.getPlayers().stream().anyMatch(p -> p.isBot()))
            .map(state -> {
                int totalSlots = state.getPlayers().size();
                long takenSlots = state.getPlayers().stream().filter(p -> !p.isBot()).count();
                int missingPlayers = (int)(totalSlots - takenSlots);
                String creatorName = state.getPlayers().stream()
                    .filter(p -> !p.isBot()).findFirst()
                    .map(p -> p.getName()).orElse("?");
                java.util.List<String> playerNames = state.getPlayers().stream()
                    .map(p -> p.isBot() ? "— posto libero —" : p.getName())
                    .collect(java.util.stream.Collectors.toList());
                return new com.sevenkingdoms.api.GameController.LobbyEntry(
                    state.getGameId(), creatorName, totalSlots,
                    (int) takenSlots, missingPlayers, playerNames
                );
            })
            .collect(java.util.stream.Collectors.toList());
    }

    public GameRepository getRepository() {
        return gameRepository;
    }
}