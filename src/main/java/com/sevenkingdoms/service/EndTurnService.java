package com.sevenkingdoms.service;

import com.sevenkingdoms.exception.GameException;
import com.sevenkingdoms.model.*;
import com.sevenkingdoms.model.enums.GamePhase;
import com.sevenkingdoms.repository.GameRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class EndTurnService {

    private final GameRepository gameRepository;
    private final VictoryService victoryService;
    private final NotificationService notificationService;

    public GameState resolveEndTurn(String gameId) {
        GameState state = gameRepository.getOrThrow(gameId);
        if (state.getPhase() != GamePhase.END_TURN) {
            throw GameException.wrongPhase("Non siamo nella fase di fine turno");
        }

        state.addEvent("=== Fine Turno " + state.getTurnNumber() + " ===");

        // 1. Costo della Guerra
        costOfWar(state);

        // 2. Controllo fine gioco
        if (state.isGameOver()) {
            state.setPhase(GamePhase.GAME_OVER);
            victoryService.calculateScores(state);
            state.addEvent("PARTITA TERMINATA!");
            GameState saved = gameRepository.save(state);
            notificationService.broadcastGameState(saved);
            return saved;
        }

        // 3. Invasione generale (province senza personaggi)
        generalInvasion(state);

        // 4. Secondo controllo fine gioco post-invasione
        if (state.isGameOver()) {
            state.setPhase(GamePhase.GAME_OVER);
            victoryService.calculateScores(state);
            state.addEvent("PARTITA TERMINATA per invasione!");
            GameState saved = gameRepository.save(state);
            notificationService.broadcastGameState(saved);
            return saved;
        }

        // 5. Inizia nuovo turno
        state.resetForNewTurn();
        state.setPhase(GamePhase.PLAYER_ACTIONS);
        state.addEvent("=== Inizio Turno " + state.getTurnNumber() + " ===");

        GameState saved = gameRepository.save(state);
        notificationService.broadcastGameState(saved);
        return saved;
    }

    // ── Costo della Guerra: ogni guerra abbassa di 1 il valore economico ────

    private void costOfWar(GameState state) {
        for (Province province : state.getProvinces()) {
            if (province.isAtWar()) {
                province.decreaseEconomic();
                state.addEvent("Costo Guerra: " + province.getName()
                        + " → valore economico " + province.getEconomicValue());
            }
        }
    }

    // ── Invasione generale: province senza personaggi ricevono cubetto invasione

    private void generalInvasion(GameState state) {
        for (Province province : state.getProvinces()) {
            if (province.isPacified() || province.isAtWar()) continue;

            boolean hasCharacter = state.getCharacters().values().stream()
                    .anyMatch(c -> c.getCurrentProvinceId() == province.getId());

            if (!hasCharacter) {
                province.addInvasionCube();
                state.addEvent("Invasione in " + province.getName());
            }
        }
    }
}
