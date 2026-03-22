package com.sevenkingdoms.service;

import com.sevenkingdoms.exception.GameException;
import com.sevenkingdoms.model.*;
import com.sevenkingdoms.model.enums.ActionType;
import com.sevenkingdoms.model.enums.CharacterType;
import com.sevenkingdoms.model.enums.GamePhase;
import com.sevenkingdoms.repository.GameRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MercanteService {

    private final GameRepository gameRepository;
    private final GameService gameService;
    private final NotificationService notificationService;

    // ── Province raggiungibili dal Mercante ───────────────────────────────────

    public List<Integer> getReachableProvinces(String gameId) {
        GameState state = gameRepository.getOrThrow(gameId);
        GameCharacter mercante = state.getCharacter(CharacterType.MERCANTE);
        Province current = state.getProvince(mercante.getCurrentProvinceId());

        List<Integer> reachable = new ArrayList<>();
        reachable.addAll(current.getAdjacentIds());
        reachable.addAll(current.getSeaAdjacentIds());

        return reachable.stream()
                .collect(Collectors.toList());
    }

    // ── Esegue l'azione del Mercante ──────────────────────────────────────────

    public GameState executeAction(String gameId, String playerId,
                                   ActionType actionType,
                                   int moveToProvinceId) {

        GameState state = gameRepository.getOrThrow(gameId);
        gameService.validatePhase(state, GamePhase.PLAYER_ACTIONS);
        gameService.validateCurrentPlayer(state, playerId);
        gameService.validatePlayerNotActed(state, playerId);

        Player player = state.getCurrentPlayer();
        GameCharacter mercante = state.getCharacter(CharacterType.MERCANTE);

        // ── 1. Movimento obbligatorio ─────────────────────────────────────────
        if (moveToProvinceId < 0) {
            throw GameException.invalidAction("Il Mercante deve spostarsi in una provincia adiacente");
        }
        Province current = state.getProvince(mercante.getCurrentProvinceId());
        if (!current.isAdjacentByLand(moveToProvinceId)
                && !current.isAdjacentBySea(moveToProvinceId)) {
            throw GameException.invalidAction("Il Mercante non può raggiungere questa provincia");
        }
        if (moveToProvinceId == mercante.getCurrentProvinceId()) {
            throw GameException.invalidAction("Il Mercante non può restare nella stessa provincia");
        }
        mercante.moveTo(moveToProvinceId);

        // ── 2. Azione ─────────────────────────────────────────────────────────
        int cubesToSend = (actionType == ActionType.POTENZIATA) ? 2 : 1;

        if (actionType == ActionType.POTENZIATA) {
            if (!player.hasPapalFavor()) {
                throw GameException.invalidAction("Azione potenziata richiede un Favore Papale");
            }
            player.spendPapalFavor();
        }

        // Prende cubetti dalla riserva e li mette nel mercato
        for (int i = 0; i < cubesToSend; i++) {
            if (!player.takeCubeFromReserve()) {
                throw GameException.invalidAction("Non hai abbastanza cubetti nella riserva");
            }
            state.addMarketCube(player.getColorIndex());
            player.setMarketCubes(player.getMarketCubes() + 1);
        }

        // ── 3. Segna azione eseguita ──────────────────────────────────────────
        mercante.markUsed();
        player.setActionDone(true);
        state.addEvent(player.getName() + " usa il Mercante: "
                + cubesToSend + " cubett" + (cubesToSend == 1 ? "o" : "i")
                + " inviati al mercato");

        advanceTurn(state);
        GameState saved = gameRepository.save(state);
        notificationService.broadcastGameState(saved);
        return saved;
    }

    private void advanceTurn(GameState state) {
        if (state.allPlayersActed()) {
            state.setPhase(GamePhase.PASSIVE_ACTIONS);
            state.addEvent("Tutti i giocatori hanno agito. Inizio fase passiva.");
        } else {
            state.advanceToNextPlayer();
        }
    }
}