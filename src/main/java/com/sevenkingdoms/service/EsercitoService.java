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
public class EsercitoService {

    private final GameRepository gameRepository;
    private final GameService gameService;
    private final NotificationService notificationService;

    // ── Province raggiungibili ────────────────────────────────────────────────

    public List<Integer> getReachableProvinces(String gameId, CharacterType armyType) {
        GameState state = gameRepository.getOrThrow(gameId);
        GameCharacter army = state.getCharacter(armyType);
        Province current = state.getProvince(army.getCurrentProvinceId());

        if (armyType == CharacterType.FANTERIA) {
            // Fanteria: 1 provincia, anche via mare
            List<Integer> reachable = new ArrayList<>(current.getAdjacentIds());
            reachable.addAll(current.getSeaAdjacentIds());
            return reachable;
        } else {
            // Cavalleria: 2 province via terra, NON via mare, NON torna indietro
            List<Integer> reachable = new ArrayList<>();
            for (int firstHop : current.getAdjacentIds()) {
                Province firstProv = state.getProvince(firstHop);
                // Dalla prima provincia, aggiunge le sue adiacenti via terra
                // escludendo la provincia di partenza
                for (int secondHop : firstProv.getAdjacentIds()) {
                    if (secondHop != army.getCurrentProvinceId()
                            && !reachable.contains(secondHop)) {
                        reachable.add(secondHop);
                    }
                }
                // Può anche fermarsi alla prima provincia adiacente
                if (!reachable.contains(firstHop)) {
                    reachable.add(firstHop);
                }
            }
            return reachable;
        }
    }

    // ── Esegue l'azione ───────────────────────────────────────────────────────

    public GameState executeAction(String gameId, String playerId,
                                   CharacterType armyType,
                                   ActionType actionType,
                                   int moveToProvinceId,
                                   boolean declareBattle) {

        GameState state = gameRepository.getOrThrow(gameId);
        gameService.validatePhase(state, GamePhase.PLAYER_ACTIONS);
        gameService.validateCurrentPlayer(state, playerId);
        gameService.validatePlayerNotActed(state, playerId);

        Player player = state.getCurrentPlayer();
        GameCharacter army = state.getCharacter(armyType);

        // ── 1. Movimento obbligatorio ─────────────────────────────────────────
        validateAndMove(state, army, armyType, moveToProvinceId);

        Province armyProvince = state.getProvince(army.getCurrentProvinceId());

        // ── 2. Azione ─────────────────────────────────────────────────────────
        if (declareBattle) {
            // Dichiara battaglia in una provincia in guerra
            declareBattle(state, player, army, armyProvince);
        } else {
            // Azione normale o potenziata: elimina cubetti invasione
            if (armyProvince.isAtWar()) {
                throw GameException.invalidAction(
                    "Non puoi fare l'azione normale in una provincia in Guerra. " +
                    "Devi dichiarare battaglia.");
            }
            if (actionType == ActionType.POTENZIATA) {
                if (!player.hasPapalFavor()) {
                    throw GameException.invalidAction("Azione potenziata richiede un Favore Papale");
                }
                player.spendPapalFavor();
                executePotenziata(state, player, army, armyProvince);
            } else {
                executeNormale(state, player, army, armyProvince);
            }
        }

        // ── 3. Segna azione eseguita ──────────────────────────────────────────
        army.markUsed();
        player.setActionDone(true);
        String armyName = armyType == CharacterType.FANTERIA ? "Fanteria" : "Cavalleria";
        state.addEvent(player.getName() + " usa " + armyName
                + " in " + armyProvince.getName());

        advanceTurn(state);
        GameState saved = gameRepository.save(state);
        notificationService.broadcastGameState(saved);
        return saved;
    }

    // ── Azione normale: elimina 1 cubetto invasione, metti il tuo ────────────

    private void executeNormale(GameState state, Player player,
                                 GameCharacter army, Province province) {
        int invasionCount = province.countInvasionCubes();
        if (invasionCount == 0) {
            throw GameException.invalidAction(
                "Non ci sono cubetti invasione da eliminare in " + province.getName());
        }

        removeOneInvasionCube(province);

        // Metti il cubetto del giocatore nel primo slot vuoto a sinistra
        if (!player.takeCubeFromReserve()) {
            throw GameException.invalidAction("Nessun cubetto disponibile nella riserva");
        }
        addCubeLeftmost(province, Cube.ofPlayer(player.getColorIndex()));

        state.addEvent("Rimosso 1 cubetto invasione da " + province.getName()
                + ", aggiunto cubetto di " + player.getName());
    }

    // ── Azione potenziata: elimina 2 cubetti invasione, metti 2 tuoi ─────────

    private void executePotenziata(GameState state, Player player,
                                    GameCharacter army, Province province) {
        int invasionCount = province.countInvasionCubes();
        if (invasionCount < 2) {
            throw GameException.invalidAction(
                "Servono almeno 2 cubetti invasione per l'azione potenziata (ce ne sono "
                + invasionCount + ")");
        }

        removeOneInvasionCube(province);
        removeOneInvasionCube(province);

        for (int i = 0; i < 2; i++) {
            if (!player.takeCubeFromReserve()) {
                throw GameException.invalidAction("Nessun cubetto disponibile nella riserva");
            }
            addCubeLeftmost(province, Cube.ofPlayer(player.getColorIndex()));
        }

        state.addEvent("Rimossi 2 cubetti invasione da " + province.getName()
                + ", aggiunti 2 cubetti di " + player.getName());
    }

    // ── Dichiara battaglia ────────────────────────────────────────────────────

    private void declareBattle(GameState state, Player player,
                                GameCharacter army, Province province) {
        if (!province.isAtWar()) {
            throw GameException.invalidAction(
                province.getName() + " non è in Guerra — non puoi dichiarare battaglia");
        }

        army.setDeclaredBattle(true);
        state.addEvent(player.getName() + " dichiara battaglia in "
                + province.getName() + "! Sarà risolta nella fase passiva.");
    }

    // ── Helper: valida e applica movimento ────────────────────────────────────

    private void validateAndMove(GameState state, GameCharacter army,
                                  CharacterType armyType, int moveToProvinceId) {
        if (moveToProvinceId < 0) {
            throw GameException.invalidAction("L'esercito deve spostarsi");
        }
        if (moveToProvinceId == army.getCurrentProvinceId()) {
            throw GameException.invalidAction("L'esercito non può restare nella stessa provincia");
        }

        List<Integer> reachable = getReachableProvinces(
                state.getGameId(), armyType);

        // Nota: per la Cavalleria verifichiamo via getReachableProvinces
        // già calcolato sopra — usiamo quello
        Province current = state.getProvince(army.getCurrentProvinceId());

        if (armyType == CharacterType.FANTERIA) {
            boolean byLand = current.isAdjacentByLand(moveToProvinceId);
            boolean bySea  = current.isAdjacentBySea(moveToProvinceId);
            if (!byLand && !bySea) {
                throw GameException.invalidAction("La Fanteria può muoversi di 1 provincia (terra o mare)");
            }
        } else {
            // Cavalleria: usa reachable già calcolato
            if (!reachable.contains(moveToProvinceId)) {
                throw GameException.invalidAction(
                    "La Cavalleria può muoversi di 2 province via terra, " +
                    "senza tornare indietro e senza mare");
            }
        }

        army.moveTo(moveToProvinceId);
    }

    // ── Helper: rimuove il primo cubetto invasione da sinistra ──────────────────

    private void removeOneInvasionCube(Province province) {
        List<Cube> track = province.getPoliticalTrack();
        for (int i = 0; i < track.size(); i++) {
            if (track.get(i).isInvasion()) {
                track.set(i, Cube.empty());
                return;
            }
        }
    }

    // ── Helper: aggiunge cubetto nel primo slot vuoto a sinistra ──────────────

    private void addCubeLeftmost(Province province, Cube cube) {
        List<Cube> track = province.getPoliticalTrack();
        for (int i = 0; i < track.size(); i++) {
            if (track.get(i).isEmpty()) {
                track.set(i, cube);
                return;
            }
        }
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