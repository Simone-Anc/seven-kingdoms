package com.sevenkingdoms.api;

import com.sevenkingdoms.auth.AppUser;
import com.sevenkingdoms.auth.AuthService;
import com.sevenkingdoms.bot.BotService;
import com.sevenkingdoms.exception.GameException;
import com.sevenkingdoms.model.GameState;
import com.sevenkingdoms.model.Player;
import com.sevenkingdoms.model.enums.ActionType;
import com.sevenkingdoms.model.enums.CharacterType;
import com.sevenkingdoms.repository.GameRepository;
import com.sevenkingdoms.service.*;
import com.sevenkingdoms.service.ReService.CubePlacementRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/games")
@RequiredArgsConstructor
@CrossOrigin(origins = "*", allowedHeaders = "*")
public class GameController {

    private final GameService gameService;
    private final PassivePhaseService passivePhaseService;
    private final EndTurnService endTurnService;
    private final ReService reService;
    private final PapaService papaService;
    private final MercanteService mercanteService;
    private final SpiaService spiaService;
    private final EsercitoService esercitoService;
    private final GameRepository gameRepository;
    private final BotService botService;
    private final AuthService authService;

    // ── Helper: verifica turno ───────────────────────────────────────────────

    /**
     * Verifica che l'utente autenticato sia il giocatore corrente nella partita.
     * Se l'utente non è autenticato (partita locale senza login) lascia passare.
     */
    private void validateTurn(String gameId, String playerId, AppUser currentUser) {
        if (currentUser == null) return; // partita locale, nessun controllo
        GameState state = gameRepository.getOrThrow(gameId);
        Player currentPlayer = state.getCurrentPlayer();
        // Verifica che il playerId nella richiesta corrisponda al giocatore corrente
        if (!currentPlayer.getId().equals(playerId)) {
            throw GameException.invalidAction("Non è il tuo turno");
        }
        // Verifica che l'utente loggato sia associato a quel player
        // (il nickname del player deve corrispondere al nickname dell'utente)
        if (!currentPlayer.getName().equalsIgnoreCase(currentUser.getNickname())
                && !currentPlayer.isBot()) {
            throw GameException.invalidAction("Non sei autorizzato ad agire per questo giocatore");
        }
    }

    // ── Lobby: partite in attesa ──────────────────────────────────────────────

    @GetMapping("/lobby")
    public ResponseEntity<List<LobbyEntry>> getLobby() {
        List<LobbyEntry> lobby = gameService.getLobbyEntries();
        return ResponseEntity.ok(lobby);
    }

    public record LobbyEntry(
        String gameId,
        String creatorName,
        int totalSlots,
        int takenSlots,
        int missingPlayers,
        List<String> playerNames
    ) {}

    // ── Health check ──────────────────────────────────────────────────────────

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }

    // ── Crea partita ──────────────────────────────────────────────────────────

    @PostMapping
    public ResponseEntity<GameState> createGame(@RequestBody CreateGameRequestInternal request) {
        List<String> names  = request.players().stream().map(PlayerSetupInternal::name).toList();
        List<String> colors = request.players().stream().map(PlayerSetupInternal::color).toList();
        List<Boolean> bots  = request.players().stream().map(p -> Boolean.TRUE.equals(p.bot())).toList();
        GameState state = gameService.createGame(names, colors, bots);
        botService.triggerBotTurnIfNeeded(state.getGameId());
        return ResponseEntity.status(HttpStatus.CREATED).body(state);
    }

    // ── Stato partita ─────────────────────────────────────────────────────────

    @GetMapping("/{gameId}")
    public ResponseEntity<GameState> getState(@PathVariable String gameId) {
        return ResponseEntity.ok(gameService.getState(gameId));
    }

    // ── Unisciti a partita esistente ──────────────────────────────────────────

    @PostMapping("/{gameId}/join")
    public ResponseEntity<GameState> joinGame(
            @PathVariable String gameId,
            @RequestBody JoinRequest request) {
        return ResponseEntity.ok(gameService.joinGame(gameId, request.playerName(), request.color()));
    }

    public record JoinRequest(String playerName, String color) {}

    // ── Setup ─────────────────────────────────────────────────────────────────

    @PostMapping("/{gameId}/setup/place-cube")
    public ResponseEntity<GameState> placeInitialCube(
            @PathVariable String gameId,
            @RequestBody Map<String, Object> body) {
        String playerId   = (String) body.get("playerId");
        int    provinceId = (int) body.get("provinceId");
        GameState state = gameService.placeInitialCube(gameId, playerId, provinceId);
        botService.triggerBotTurnIfNeeded(gameId);
        return ResponseEntity.ok(state);
    }

    // ── RE ────────────────────────────────────────────────────────────────────

    @GetMapping("/{gameId}/actions/re/reachable")
    public ResponseEntity<List<Integer>> getReReachable(@PathVariable String gameId) {
        return ResponseEntity.ok(reService.getReachableProvinces(gameId));
    }

    @GetMapping("/{gameId}/actions/re/placeable")
    public ResponseEntity<List<Integer>> getRePlaceable(
            @PathVariable String gameId, @RequestParam int movedTo) {
        return ResponseEntity.ok(reService.getPlaceableProvinces(gameId, movedTo));
    }

    @PostMapping("/{gameId}/actions/re")
    public ResponseEntity<GameState> actionRe(
            @PathVariable String gameId,
            @RequestBody ReActionRequest request) {
        List<CubePlacementRequest> placements = request.cubePlacements() == null
                ? List.of()
                : request.cubePlacements().stream()
                    .map(p -> new CubePlacementRequest(p.provinceId(), p.playerIndex()))
                    .toList();
        GameState state = reService.executeAction(gameId, request.playerId(),
                ActionType.valueOf(request.actionType()),
                request.moveToProvinceId(), placements);
        botService.triggerBotTurnIfNeeded(gameId);
        return ResponseEntity.ok(state);
    }

    // ── PAPA ──────────────────────────────────────────────────────────────────

    @GetMapping("/{gameId}/actions/papa/reachable")
    public ResponseEntity<List<Integer>> getPapaReachable(@PathVariable String gameId) {
        return ResponseEntity.ok(papaService.getReachableProvinces(gameId));
    }

    @GetMapping("/{gameId}/actions/papa/religious-targets")
    public ResponseEntity<List<Integer>> getPapaReligiousTargets(
            @PathVariable String gameId,
            @RequestParam int movedTo, @RequestParam String actionType) {
        return ResponseEntity.ok(papaService.getReligiousTargets(
                gameId, movedTo, ActionType.valueOf(actionType)));
    }

    @PostMapping("/{gameId}/actions/papa")
    public ResponseEntity<GameState> actionPapa(
            @PathVariable String gameId,
            @RequestBody PapaActionRequest request) {
        GameState state = papaService.executeAction(gameId, request.playerId(),
                ActionType.valueOf(request.actionType()), request.moveToProvinceId(),
                request.religiousTargetProvinceId(), request.cathedralProvinceId());
        botService.triggerBotTurnIfNeeded(gameId);
        return ResponseEntity.ok(state);
    }

    // ── MERCANTE ──────────────────────────────────────────────────────────────

    @GetMapping("/{gameId}/actions/mercante/reachable")
    public ResponseEntity<List<Integer>> getMercanteReachable(@PathVariable String gameId) {
        return ResponseEntity.ok(mercanteService.getReachableProvinces(gameId));
    }

    @PostMapping("/{gameId}/actions/mercante")
    public ResponseEntity<GameState> actionMercante(
            @PathVariable String gameId,
            @RequestBody MercanteActionRequest request) {
        GameState state = mercanteService.executeAction(gameId, request.playerId(),
                ActionType.valueOf(request.actionType()), request.moveToProvinceId());
        botService.triggerBotTurnIfNeeded(gameId);
        return ResponseEntity.ok(state);
    }

    // ── SPIA ──────────────────────────────────────────────────────────────────

    @GetMapping("/{gameId}/actions/spia/reachable")
    public ResponseEntity<List<Integer>> getSpiaReachable(@PathVariable String gameId) {
        return ResponseEntity.ok(spiaService.getReachableProvinces(gameId));
    }

    @GetMapping("/{gameId}/actions/spia/swap-targets")
    public ResponseEntity<List<Integer>> getSpiaSwapTargets(
            @PathVariable String gameId, @RequestParam int movedTo) {
        return ResponseEntity.ok(spiaService.getSwapTargets(gameId, movedTo));
    }

    @PostMapping("/{gameId}/actions/spia")
    public ResponseEntity<GameState> actionSpia(
            @PathVariable String gameId,
            @RequestBody SpiaActionRequest request) {
        GameState state = spiaService.executeAction(gameId, request.playerId(),
                ActionType.valueOf(request.actionType()), request.moveToProvinceId(),
                request.swapA() != null ? new SpiaService.SwapRequest(
                        request.swapA().provinceId(), request.swapA().slotIndex()) : null,
                request.swapB() != null ? new SpiaService.SwapRequest(
                        request.swapB().provinceId(), request.swapB().slotIndex()) : null);
        botService.triggerBotTurnIfNeeded(gameId);
        return ResponseEntity.ok(state);
    }

    // ── ESERCITO ──────────────────────────────────────────────────────────────

    @GetMapping("/{gameId}/actions/esercito/{armyType}/reachable")
    public ResponseEntity<List<Integer>> getEsercitoReachable(
            @PathVariable String gameId, @PathVariable String armyType) {
        return ResponseEntity.ok(esercitoService.getReachableProvinces(
                gameId, CharacterType.valueOf(armyType.toUpperCase())));
    }

    @PostMapping("/{gameId}/actions/esercito")
    public ResponseEntity<GameState> actionEsercito(
            @PathVariable String gameId,
            @RequestBody EsercitoActionRequest request) {
        GameState state = esercitoService.executeAction(gameId, request.playerId(),
                CharacterType.valueOf(request.armyType().toUpperCase()),
                ActionType.valueOf(request.actionType()),
                request.moveToProvinceId(),
                request.declareBattle() != null && request.declareBattle());
        botService.triggerBotTurnIfNeeded(gameId);
        return ResponseEntity.ok(state);
    }

    // ── PASSIVA MERCANTE ──────────────────────────────────────────────────────

    @GetMapping("/{gameId}/passive/mercante-targets")
    public ResponseEntity<List<Integer>> getMercanteEcoTargets(@PathVariable String gameId) {
        GameState state = gameRepository.getOrThrow(gameId);
        return ResponseEntity.ok(passivePhaseService.getMercanteEcoTargets(state));
    }

    @PostMapping("/{gameId}/passive/mercante-eco")
    public ResponseEntity<GameState> resolveMercanteEco(
            @PathVariable String gameId,
            @RequestBody Map<String, Object> body) {
        String playerId = (String) body.get("playerId");
        int provinceId  = (int) body.get("provinceId");
        GameState state = passivePhaseService.resolveMercanteEco(gameId, playerId, provinceId);
        botService.triggerBotTurnIfNeeded(gameId);
        return ResponseEntity.ok(state);
    }

    // ── Fase passiva ──────────────────────────────────────────────────────────

    @PostMapping("/{gameId}/actions/passive")
    public ResponseEntity<GameState> resolvePassive(@PathVariable String gameId) {
        GameState state = passivePhaseService.resolvePassivePhase(gameId);
        botService.triggerBotTurnIfNeeded(gameId);
        return ResponseEntity.ok(state);
    }

    // ── Fine turno ────────────────────────────────────────────────────────────

    @PostMapping("/{gameId}/actions/end-turn")
    public ResponseEntity<GameState> resolveEndTurn(@PathVariable String gameId) {
        GameState state = endTurnService.resolveEndTurn(gameId);
        botService.triggerBotTurnIfNeeded(gameId);
        return ResponseEntity.ok(state);
    }

    // ── Inner DTOs ────────────────────────────────────────────────────────────

    public record CreateGameRequestInternal(List<PlayerSetupInternal> players) {}
    public record PlayerSetupInternal(String name, String color, Boolean bot) {}

    public record ReActionRequest(
            String playerId, String actionType,
            int moveToProvinceId, List<CubePlacement> cubePlacements) {}
    public record CubePlacement(int provinceId, int playerIndex) {}

    public record PapaActionRequest(
            String playerId, String actionType,
            int moveToProvinceId, int religiousTargetProvinceId,
            Integer cathedralProvinceId) {}

    public record MercanteActionRequest(
            String playerId, String actionType, int moveToProvinceId) {}

    public record SpiaActionRequest(
            String playerId, String actionType,
            int moveToProvinceId, SwapPosition swapA, SwapPosition swapB) {}
    public record SwapPosition(int provinceId, int slotIndex) {}

    public record EsercitoActionRequest(
            String playerId, String armyType, String actionType,
            int moveToProvinceId, Boolean declareBattle) {}
}