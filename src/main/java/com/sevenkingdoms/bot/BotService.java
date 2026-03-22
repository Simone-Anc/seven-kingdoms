package com.sevenkingdoms.bot;

import com.sevenkingdoms.model.*;
import com.sevenkingdoms.model.enums.GamePhase;
import com.sevenkingdoms.model.enums.CharacterType;
import com.sevenkingdoms.repository.GameRepository;
import com.sevenkingdoms.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Random;

@Slf4j
@Service
@RequiredArgsConstructor
public class BotService {

    private final GameRepository gameRepository;
    private final StrategicBotStrategy strategy;
    private final GameService gameService;
    private final ReService reService;
    private final PapaService papaService;
    private final MercanteService mercanteService;
    private final SpiaService spiaService;
    private final EsercitoService esercitoService;
    private final PassivePhaseService passivePhaseService;
    private final EndTurnService endTurnService;
    private final NotificationService notificationService;

    private final Random random = new Random();

    // ── Entry point: chiamato dopo ogni azione umana ──────────────────────────

    @Async("taskExecutor")
    public void triggerBotTurnIfNeeded(String gameId) {
        try {
            processBotLoop(gameId);
        } catch (Exception e) {
            log.error("Errore nel loop bot per partita {}: {}", gameId, e.getMessage(), e);
        }
    }

    // ── Loop principale: continua finché c'è un bot da far agire ─────────────

    private void processBotLoop(String gameId) throws InterruptedException {
        // Max 50 iterazioni per sicurezza
        for (int iter = 0; iter < 50; iter++) {
            Thread.sleep(300);
            GameState state = gameRepository.getOrThrow(gameId);

            if (state.getPhase() == GamePhase.GAME_OVER) return;

            // ── SETUP ─────────────────────────────────────────────────────────
            if (state.getPhase() == GamePhase.SETUP) {
                Player current = state.getCurrentPlayer();
                if (!current.isBot()) return; // aspetta l'umano
                Thread.sleep(800 + random.nextInt(600));
                executeBotSetup(gameId, current);
                continue;
            }

            // ── PLAYER_ACTIONS ────────────────────────────────────────────────
            if (state.getPhase() == GamePhase.PLAYER_ACTIONS) {
                Player current = state.getCurrentPlayer();
                if (!current.isBot()) return; // aspetta l'umano
                Thread.sleep(1000 + random.nextInt(800));
                executeBotAction(gameId, current, state);
                continue;
            }

            // ── PASSIVE_ACTIONS ───────────────────────────────────────────────
            if (state.getPhase() == GamePhase.PASSIVE_ACTIONS) {
                // Scelta eco Mercante in attesa
                if ("MERCANTE_CHOOSE".equals(state.getPassiveSubStep())) {
                    int botIdx = state.getPendingMercantePlayerIndex();
                    Player chooser = state.getPlayers().get(botIdx);
                    if (!chooser.isBot()) return; // aspetta l'umano
                    Thread.sleep(800 + random.nextInt(400));
                    executeBotMercanteEco(gameId, botIdx, state);
                    continue;
                }
                // Risolvi la fase passiva automaticamente solo se tutti bot
                // altrimenti l'umano clicca il bottone
                boolean allBotsPassive = state.getPlayers().stream().allMatch(Player::isBot);
                if (!allBotsPassive) break; // esce dal loop, aspetta click umano
                Thread.sleep(600 + random.nextInt(400));
                passivePhaseService.resolvePassivePhase(gameId);
                continue;
            }

            // ── END_TURN ──────────────────────────────────────────────────────
            // Risolvi automaticamente solo se TUTTI i giocatori sono bot
            // Se c'è almeno un umano, lascia che sia lui a cliccare "Fine Turno"
            if (state.getPhase() == GamePhase.END_TURN) {
                boolean allBots = state.getPlayers().stream().allMatch(Player::isBot);
                if (!allBots) break; // esce dal loop, aspetta click umano
                Thread.sleep(600 + random.nextInt(400));
                endTurnService.resolveEndTurn(gameId);
                continue;
            }

            break;
        }
    }

    // ── Setup: piazza cubetto nella miglior provincia ─────────────────────────

    private void executeBotSetup(String gameId, Player bot) {
        GameState state = gameRepository.getOrThrow(gameId);
        int botIdx = state.getCurrentPlayerIndex();

        int bestProvinceId = strategy.chooseSetupProvince(state, botIdx);

        try {
            gameService.placeInitialCube(gameId, bot.getId(), bestProvinceId);
            log.info("Bot {} piazza cubetto in provincia {}", bot.getName(), bestProvinceId);
        } catch (Exception e) {
            log.error("Errore setup bot {}: {}", bot.getName(), e.getMessage());
        }
    }

    // ── Azione principale ─────────────────────────────────────────────────────

    private void executeBotAction(String gameId, Player bot, GameState state) {
        int botIdx = state.getPlayers().indexOf(bot);
        BotAction action = strategy.chooseAction(state, botIdx);

        if (action == null) {
            log.warn("Bot {} non ha azioni disponibili", bot.getName());
            return;
        }

        try {
            executeChosenAction(gameId, bot, action);
            log.info("Bot {} → {} {} score={}", bot.getName(),
                    action.getCharacter(), action.getActionType(),
                    String.format("%.2f", action.getScore()));
        } catch (Exception e) {
            log.error("Errore azione bot {}: {}", bot.getName(), e.getMessage());
            // Fallback: usa il Mercante con azione normale
            try {
                executeFallbackAction(gameId, bot, state, botIdx);
            } catch (Exception ex) {
                log.error("Errore anche nel fallback: {}", ex.getMessage());
            }
        }
    }

    // ── Fallback: usa il primo personaggio disponibile ────────────────────────

    private void executeFallbackAction(String gameId, Player bot,
                                        GameState state, int botIdx) throws Exception {
        for (CharacterType ct : CharacterType.values()) {
            GameCharacter ch = state.getCharacter(ct);
            if (ch.isUsedThisTurn()) continue;

            Province current = state.getProvince(ch.getCurrentProvinceId());
            List<Integer> adj = current.getAdjacentIds();
            if (adj.isEmpty()) continue;

            int dest = adj.get(0);

            switch (ct) {
                case MERCANTE -> mercanteService.executeAction(gameId, bot.getId(),
                        com.sevenkingdoms.model.enums.ActionType.NORMALE, dest);
                default -> { continue; }
            }
            return;
        }
    }

    // ── Esegue l'azione scelta ────────────────────────────────────────────────

    private void executeChosenAction(String gameId, Player bot, BotAction action) throws Exception {
        String pid = bot.getId();
        switch (action.getCharacter()) {
            case RE -> {
                List<ReService.CubePlacementRequest> pl = action.getCubePlacements() == null
                        ? List.of()
                        : action.getCubePlacements().stream()
                            .map(p -> new ReService.CubePlacementRequest(
                                    p.getProvinceId(), p.getPlayerIndex()))
                            .toList();
                reService.executeAction(gameId, pid, action.getActionType(),
                        action.getMoveToProvinceId(), pl);
            }
            case PAPA -> papaService.executeAction(gameId, pid, action.getActionType(),
                    action.getMoveToProvinceId(), action.getReligiousTargetProvinceId(),
                    action.getCathedralProvinceId());
            case MERCANTE -> mercanteService.executeAction(gameId, pid,
                    action.getActionType(), action.getMoveToProvinceId());
            case SPIA -> {
                SpiaService.SwapRequest swapA = action.getSwapAProvinceId() != null
                        ? new SpiaService.SwapRequest(action.getSwapAProvinceId(),
                                action.getSwapASlotIndex()) : null;
                SpiaService.SwapRequest swapB = action.getSwapBProvinceId() != null
                        ? new SpiaService.SwapRequest(action.getSwapBProvinceId(),
                                action.getSwapBSlotIndex()) : null;
                spiaService.executeAction(gameId, pid, action.getActionType(),
                        action.getMoveToProvinceId(), swapA, swapB);
            }
            case FANTERIA, CAVALLERIA -> esercitoService.executeAction(gameId, pid,
                    action.getCharacter(), action.getActionType(),
                    action.getMoveToProvinceId(), action.isDeclareBattle());
        }
    }

    // ── Scelta eco Mercante ───────────────────────────────────────────────────

    private void executeBotMercanteEco(String gameId, int botIdx, GameState state) {
        Player bot = state.getPlayers().get(botIdx);
        List<Integer> targets = state.getPendingMercanteEcoTargets();
        if (targets.isEmpty()) return;

        int chosenId = strategy.chooseMercanteEcoTarget(state, botIdx, targets);
        try {
            passivePhaseService.resolveMercanteEco(gameId, bot.getId(), chosenId);
            log.info("Bot {} sceglie eco in provincia {}", bot.getName(), chosenId);
        } catch (Exception e) {
            log.error("Errore eco bot: {}", e.getMessage());
        }
    }
}