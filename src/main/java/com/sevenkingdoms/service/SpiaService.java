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
public class SpiaService {

    private final GameRepository gameRepository;
    private final GameService gameService;
    private final NotificationService notificationService;

    public record SwapRequest(int provinceId, int slotIndex) {}

    // ── Province raggiungibili dalla Spia ─────────────────────────────────────

    public List<Integer> getReachableProvinces(String gameId) {
        GameState state = gameRepository.getOrThrow(gameId);
        GameCharacter spia = state.getCharacter(CharacterType.SPIA);
        Province current = state.getProvince(spia.getCurrentProvinceId());

        List<Integer> reachable = new ArrayList<>();
        reachable.addAll(current.getAdjacentIds());
        reachable.addAll(current.getSeaAdjacentIds());

        return reachable.stream()
                .collect(Collectors.toList());
    }

    // ── Province disponibili per lo scambio ───────────────────────────────────

    public List<Integer> getSwapTargets(String gameId, int movedToProvinceId) {
        GameState state = gameRepository.getOrThrow(gameId);
        Province spiaProv = state.getProvince(movedToProvinceId);
        List<Integer> targets = spiaProv.getAdjacentIds().stream().collect(Collectors.toList());
        targets.add(movedToProvinceId);
        return targets;
    }

    // ── Esegue l'azione della Spia ────────────────────────────────────────────

    public GameState executeAction(String gameId, String playerId,
                                   ActionType actionType,
                                   int moveToProvinceId,
                                   SwapRequest swapA,
                                   SwapRequest swapB) {

        GameState state = gameRepository.getOrThrow(gameId);
        gameService.validatePhase(state, GamePhase.PLAYER_ACTIONS);
        gameService.validateCurrentPlayer(state, playerId);
        gameService.validatePlayerNotActed(state, playerId);

        Player player = state.getCurrentPlayer();
        GameCharacter spia = state.getCharacter(CharacterType.SPIA);

        // ── 1. Movimento obbligatorio ─────────────────────────────────────────
        if (moveToProvinceId < 0) {
            throw GameException.invalidAction("La Spia deve spostarsi in una provincia adiacente");
        }
        Province current = state.getProvince(spia.getCurrentProvinceId());
        if (!current.isAdjacentByLand(moveToProvinceId)
                && !current.isAdjacentBySea(moveToProvinceId)) {
            throw GameException.invalidAction("La Spia non può raggiungere questa provincia");
        }
        if (moveToProvinceId == spia.getCurrentProvinceId()) {
            throw GameException.invalidAction("La Spia non può restare nella stessa provincia");
        }
        spia.moveTo(moveToProvinceId);

        // ── 2. Azione ─────────────────────────────────────────────────────────
        if (actionType == ActionType.POTENZIATA) {
            if (!player.hasPapalFavor()) {
                throw GameException.invalidAction("Azione potenziata richiede un Favore Papale");
            }
            player.spendPapalFavor();
            executePotenziata(state, spia, swapA, swapB);
        } else {
            executeNormale(state, spia, swapA, swapB);
        }

        // ── 3. Segna azione eseguita ──────────────────────────────────────────
        spia.markUsed();
        player.setActionDone(true);
        state.addEvent(player.getName() + " usa la Spia in "
                + state.getProvince(spia.getCurrentProvinceId()).getName());

        advanceTurn(state);
        GameState saved = gameRepository.save(state);
        notificationService.broadcastGameState(saved);
        return saved;
    }

    // ── Azione normale ────────────────────────────────────────────────────────
    // Scambia 2 cubetti tra la provincia della Spia e una adiacente
    // NON può spostare i Governatori (slot 0)

    private void executeNormale(GameState state, GameCharacter spia,
                                 SwapRequest swapA, SwapRequest swapB) {
        if (swapA == null || swapB == null) {
            throw GameException.invalidAction("Devi specificare i due cubetti da scambiare");
        }

        // I due cubetti devono essere in province diverse
        if (swapA.provinceId() == swapB.provinceId()) {
            throw GameException.invalidAction("I due cubetti devono essere in province diverse");
        }

        // Almeno una deve essere la provincia della Spia
        int spiaProvId = spia.getCurrentProvinceId();
        Province spiaProv = state.getProvince(spiaProvId);
        boolean aIsSpiaProv = swapA.provinceId() == spiaProvId;
        boolean bIsSpiaProv = swapB.provinceId() == spiaProvId;
        if (!aIsSpiaProv && !bIsSpiaProv) {
            throw GameException.invalidAction("Almeno un cubetto deve essere nella provincia della Spia");
        }

        // L'altra provincia deve essere adiacente via terra
        int otherProvId = aIsSpiaProv ? swapB.provinceId() : swapA.provinceId();
        if (!spiaProv.isAdjacentByLand(otherProvId)) {
            throw GameException.invalidAction("La seconda provincia deve essere adiacente via terra alla Spia");
        }

        // Normale: NON può scambiare Governatori (slot 0)
        if (swapA.slotIndex() == 0 || swapB.slotIndex() == 0) {
            throw GameException.invalidAction("L'azione normale non può scambiare il Governatore (slot 0)");
        }

        performSwap(state, swapA, swapB);
    }

    // ── Azione potenziata ─────────────────────────────────────────────────────
    // Scambia 1 cubetto tra la provincia della Spia e una adiacente o il mercato
    // PUÒ scambiare i Governatori

    private void executePotenziata(GameState state, GameCharacter spia,
                                    SwapRequest swapA, SwapRequest swapB) {
        if (swapA == null || swapB == null) {
            throw GameException.invalidAction("Devi specificare i due cubetti da scambiare");
        }

        if (swapA.provinceId() == swapB.provinceId()) {
            throw GameException.invalidAction("I due cubetti devono essere in province diverse");
        }

        int spiaProvId = spia.getCurrentProvinceId();
        Province spiaProv = state.getProvince(spiaProvId);
        boolean aIsSpiaProv = swapA.provinceId() == spiaProvId;
        boolean bIsSpiaProv = swapB.provinceId() == spiaProvId;
        if (!aIsSpiaProv && !bIsSpiaProv) {
            throw GameException.invalidAction("Almeno un cubetto deve essere nella provincia della Spia");
        }

        // Potenziata: può usare anche province non adiacenti (incluso mercato = provinceId -1)
        int otherProvId = aIsSpiaProv ? swapB.provinceId() : swapA.provinceId();
        if (otherProvId >= 0 && !spiaProv.isAdjacentByLand(otherProvId)) {
            throw GameException.invalidAction("La seconda provincia deve essere adiacente o il mercato");
        }

        performSwap(state, swapA, swapB);
    }

    // ── Helper: esegui lo scambio ─────────────────────────────────────────────

    private void performSwap(GameState state, SwapRequest swapA, SwapRequest swapB) {
        List<Cube> trackA = state.getProvince(swapA.provinceId()).getPoliticalTrack();
        List<Cube> trackB = state.getProvince(swapB.provinceId()).getPoliticalTrack();

        if (swapA.slotIndex() >= trackA.size()) {
            throw GameException.invalidAction("Slot non valido nella provincia A");
        }
        if (swapB.slotIndex() >= trackB.size()) {
            throw GameException.invalidAction("Slot non valido nella provincia B");
        }

        // Controlla che non siano slot vuoti
        Cube cubeA = trackA.get(swapA.slotIndex());
        Cube cubeB = trackB.get(swapB.slotIndex());

        if (cubeA.isEmpty()) {
            throw GameException.invalidAction("Lo slot A è vuoto, non c'è nulla da scambiare");
        }
        if (cubeB.isEmpty()) {
            throw GameException.invalidAction("Lo slot B è vuoto, non c'è nulla da scambiare");
        }

        // Scambio
        trackA.set(swapA.slotIndex(), cubeB);
        trackB.set(swapB.slotIndex(), cubeA);

        state.addEvent("🕵️ Spia: scambiati cubetti tra "
                + state.getProvince(swapA.provinceId()).getName()
                + " (slot " + swapA.slotIndex() + ") e "
                + state.getProvince(swapB.provinceId()).getName()
                + " (slot " + swapB.slotIndex() + ")");
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