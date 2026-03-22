package com.sevenkingdoms.bot;

import com.sevenkingdoms.model.*;
import com.sevenkingdoms.model.enums.ActionType;
import com.sevenkingdoms.model.enums.CharacterType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class StrategicBotStrategy {

    public BotAction chooseAction(GameState state, int botPlayerIndex) {
        Player bot = state.getPlayers().get(botPlayerIndex);
        int turn = state.getTurnNumber();
        List<BotAction> candidates = new ArrayList<>();

        for (CharacterType charType : CharacterType.values()) {
            GameCharacter character = state.getCharacter(charType);
            if (character.isUsedThisTurn()) continue;

            // Moltiplicatore di priorità per turno
            double priority = BotEvaluator.characterPriorityMultiplier(charType, turn);

            BotAction normal = evaluateAction(state, botPlayerIndex, charType, ActionType.NORMALE);
            if (normal != null) {
                normal.setScore(normal.getScore() * priority);
                candidates.add(normal);
            }

            if (bot.hasPapalFavor()) {
                BotAction boosted = evaluateAction(state, botPlayerIndex, charType, ActionType.POTENZIATA);
                if (boosted != null) {
                    boosted.setScore(boosted.getScore() * priority);
                    candidates.add(boosted);
                }
            }
        }

        if (candidates.isEmpty()) return null;

        BotAction best = candidates.stream()
                .max(Comparator.comparingDouble(BotAction::getScore))
                .orElse(null);

        log.info("Bot {} turno {}: {} {} score={}", bot.getName(), turn,
                best.getCharacter(), best.getActionType(),
                String.format("%.2f", best.getScore()));
        return best;
    }

    // ── Scelta setup: province senza Governatore prima ────────────────────────

    public int chooseSetupProvince(GameState state, int botPlayerIndex) {
        return state.getProvinces().stream()
                .filter(p -> !p.isFull())
                .max(Comparator.comparingDouble(p ->
                        BotEvaluator.setupProvinceValue(p, botPlayerIndex, state.getPlayers())))
                .map(Province::getId)
                .orElse(0);
    }

    // ── Scelta eco Mercante ───────────────────────────────────────────────────

    public int chooseMercanteEcoTarget(GameState state, int botPlayerIndex,
                                        List<Integer> availableTargets) {
        return availableTargets.stream()
                .max(Comparator.comparingDouble(id ->
                        BotEvaluator.provinceValue(state.getProvince(id), botPlayerIndex,
                                state.getPlayers())))
                .orElse(availableTargets.get(0));
    }

    // ── Dispatcher ────────────────────────────────────────────────────────────

    private BotAction evaluateAction(GameState state, int botIdx,
                                      CharacterType charType, ActionType actionType) {
        return switch (charType) {
            case RE         -> evaluateRe(state, botIdx, actionType);
            case PAPA       -> evaluatePapa(state, botIdx, actionType);
            case MERCANTE   -> evaluateMercante(state, botIdx, actionType);
            case SPIA       -> evaluateSpia(state, botIdx, actionType);
            case FANTERIA   -> evaluateEsercito(state, botIdx, CharacterType.FANTERIA, actionType);
            case CAVALLERIA -> evaluateEsercito(state, botIdx, CharacterType.CAVALLERIA, actionType);
        };
    }

    // ── Re ────────────────────────────────────────────────────────────────────

    private BotAction evaluateRe(GameState state, int botIdx, ActionType actionType) {
        GameCharacter re = state.getCharacter(CharacterType.RE);
        Province current = state.getProvince(re.getCurrentProvinceId());

        List<Integer> reachable = new ArrayList<>(current.getAdjacentIds());
        reachable.addAll(current.getSeaAdjacentIds());
        reachable = reachable.stream()
                .filter(id -> !state.getProvince(id).isAtWar())
                .collect(Collectors.toList());

        if (reachable.isEmpty()) return null;

        BotAction bestAction = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (int destId : reachable) {
            Province dest = state.getProvince(destId);
            if (dest.isAtWar()) continue;

            double score;

            if (actionType == ActionType.NORMALE) {
                List<BotAction.CubePlacement> placements =
                        buildNormalPlacements(state, botIdx, re, destId);
                if (placements.isEmpty()) continue;

                score = placements.stream()
                        .mapToDouble(p -> BotEvaluator.provinceValue(
                                state.getProvince(p.getProvinceId()), botIdx, state.getPlayers()))
                        .sum();
                // Bonus extra se la destinazione non ha Governatore
                if (!hasGovernor(dest)) score += 4.0;

            } else {
                // Potenziata: 2 cubetti bot in 2ª/3ª posizione
                score = BotEvaluator.provinceValue(dest, botIdx, state.getPlayers()) * 1.5;
                if (!hasGovernor(dest)) score += 2.0;
            }

            if (score > bestScore) {
                bestScore = score;
                List<BotAction.CubePlacement> pl = actionType == ActionType.NORMALE
                        ? buildNormalPlacements(state, botIdx, re, destId)
                        : List.of(
                            BotAction.CubePlacement.builder().provinceId(destId).playerIndex(botIdx).build(),
                            BotAction.CubePlacement.builder().provinceId(destId).playerIndex(botIdx).build());
                bestAction = BotAction.builder()
                        .character(CharacterType.RE)
                        .actionType(actionType)
                        .moveToProvinceId(destId)
                        .cubePlacements(pl)
                        .score(bestScore)
                        .build();
            }
        }
        return bestAction;
    }

    private boolean hasGovernor(Province province) {
        if (province.getPoliticalTrack().isEmpty()) return false;
        Cube gov = province.getPoliticalTrack().get(0);
        return gov.isColored();
    }

    private List<BotAction.CubePlacement> buildNormalPlacements(
            GameState state, int botIdx, GameCharacter re, int destId) {

        List<BotAction.CubePlacement> placements = new ArrayList<>();
        Province dest = state.getProvince(destId);
        int otherIdx = findOtherPlayerWithCubes(state, botIdx);

        if (!dest.isFull()) {
            placements.add(BotAction.CubePlacement.builder()
                    .provinceId(destId).playerIndex(botIdx).build());
        }
        if (!dest.isFull()) {
            int secondIdx = otherIdx >= 0 ? otherIdx : botIdx;
            placements.add(BotAction.CubePlacement.builder()
                    .provinceId(destId).playerIndex(secondIdx).build());
        }

        // 1 cubetto adiacente
        Province destProv = state.getProvince(destId);
        Optional<Province> bestAdj = destProv.getAdjacentIds().stream()
                .map(state::getProvince)
                .filter(p -> !p.isAtWar() && !p.isFull())
                .max(Comparator.comparingDouble(p ->
                        BotEvaluator.provinceValue(p, botIdx, state.getPlayers())));

        if (bestAdj.isPresent() && placements.size() == 2) {
            int adjIdx = otherIdx >= 0 ? otherIdx : botIdx;
            placements.add(BotAction.CubePlacement.builder()
                    .provinceId(bestAdj.get().getId()).playerIndex(adjIdx).build());
        }

        if (placements.size() == 1) placements.get(0).setPlayerIndex(botIdx);
        return placements;
    }

    private int findOtherPlayerWithCubes(GameState state, int botIdx) {
        for (int i = 0; i < state.getPlayers().size(); i++) {
            if (i != botIdx && state.getPlayers().get(i).getCubesInReserve() > 0) return i;
        }
        return -1;
    }

    // ── Papa ──────────────────────────────────────────────────────────────────

    private BotAction evaluatePapa(GameState state, int botIdx, ActionType actionType) {
        GameCharacter papa = state.getCharacter(CharacterType.PAPA);
        Province current = state.getProvince(papa.getCurrentProvinceId());
        Player bot = state.getPlayers().get(botIdx);

        List<Integer> reachable = new ArrayList<>(current.getAdjacentIds());
        reachable.addAll(current.getSeaAdjacentIds());
        if (reachable.isEmpty()) return null;

        // Trova la migliore cattedrale per il vescovo (usata solo per potenziata)
        Optional<Province> bestCathedral = state.getProvinces().stream()
                .filter(p -> p.getCathedralPlayerIndex() < 0) // libera
                .max(Comparator.comparingDouble(BotEvaluator::bishopUrgency));

        // Urgenza vescovo: alta se c'è una provincia con >=2 segnalini e cattedrale libera
        double bishopBonus = 0;
        Integer cathedralId = null;
        if (actionType == ActionType.POTENZIATA && bestCathedral.isPresent()) {
            bishopBonus = BotEvaluator.bishopUrgency(bestCathedral.get());
            cathedralId = bestCathedral.get().getId();
        }

        BotAction bestAction = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (int destId : reachable) {
            Province destProv = state.getProvince(destId);
            List<Integer> religTargets = new ArrayList<>(destProv.getAdjacentIds());
            religTargets.add(destId);

            for (int targetId : religTargets) {
                Province target = state.getProvince(targetId);
                if (target.isEconomicFullOfReligious()) continue;
                if (actionType == ActionType.NORMALE && target.isAtWar()) continue;

                long botCubes = target.getPoliticalTrack().stream()
                        .filter(c -> c.isColored() && c.getPlayerIndex() == botIdx).count();
                long otherCubes = target.getPoliticalTrack().stream()
                        .filter(c -> c.isColored() && c.getPlayerIndex() != botIdx).count();

                // Preferisce mettere segnalino dove avversari hanno più cubetti
                double score = target.getEconomicValue() * (otherCubes - botCubes + 1);
                score += bishopBonus; // bonus urgenza vescovo per potenziata

                // Extra: se la potenziata permette di mettere vescovo in provincia
                // con molti segnalini, è molto vantaggioso
                if (actionType == ActionType.POTENZIATA && bishopBonus >= 6.0) {
                    score += 5.0; // urgenza alta → priorità azione potenziata
                }

                if (score > bestScore) {
                    bestScore = score;
                    bestAction = BotAction.builder()
                            .character(CharacterType.PAPA)
                            .actionType(actionType)
                            .moveToProvinceId(destId)
                            .religiousTargetProvinceId(targetId)
                            .cathedralProvinceId(cathedralId)
                            .score(score)
                            .build();
                }
            }
        }
        return bestAction;
    }

    // ── Mercante ──────────────────────────────────────────────────────────────

    private BotAction evaluateMercante(GameState state, int botIdx, ActionType actionType) {
        GameCharacter mercante = state.getCharacter(CharacterType.MERCANTE);
        Province current = state.getProvince(mercante.getCurrentProvinceId());

        List<Integer> reachable = new ArrayList<>(current.getAdjacentIds());
        reachable.addAll(current.getSeaAdjacentIds());
        if (reachable.isEmpty()) return null;

        int destId = reachable.get(0);
        int marketCubes = state.getMarketCubes().get(botIdx);
        double score = (actionType == ActionType.POTENZIATA ? 8.0 : 5.0) - marketCubes * 0.3;

        return BotAction.builder()
                .character(CharacterType.MERCANTE)
                .actionType(actionType)
                .moveToProvinceId(destId)
                .score(score)
                .build();
    }

    // ── Spia ──────────────────────────────────────────────────────────────────

    private BotAction evaluateSpia(GameState state, int botIdx, ActionType actionType) {
        GameCharacter spia = state.getCharacter(CharacterType.SPIA);
        Province current = state.getProvince(spia.getCurrentProvinceId());

        List<Integer> reachable = new ArrayList<>(current.getAdjacentIds());
        reachable.addAll(current.getSeaAdjacentIds());
        if (reachable.isEmpty()) return null;

        BotAction bestAction = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (int destId : reachable) {
            Province destProv = state.getProvince(destId);
            List<Integer> swapTargets = new ArrayList<>(destProv.getAdjacentIds());
            swapTargets.add(destId);

            for (int targetId : swapTargets) {
                Province targetProv = state.getProvince(targetId);
                List<Cube> track = targetProv.getPoliticalTrack();

                for (int i = 1; i < track.size(); i++) {
                    Cube cubeI = track.get(i);
                    if (!cubeI.isColored() || cubeI.getPlayerIndex() == botIdx) continue;

                    for (int j = 1; j < track.size(); j++) {
                        if (i == j) continue;
                        Cube cubeJ = track.get(j);
                        if (!cubeJ.isColored() || cubeJ.getPlayerIndex() != botIdx) continue;

                        // Vale se sposto avversario a destra (posizione più alta = più PV)
                        double score = i < j ? 3.0 : -0.5;
                        if (score > bestScore) {
                            bestScore = score;
                            bestAction = BotAction.builder()
                                    .character(CharacterType.SPIA)
                                    .actionType(actionType)
                                    .moveToProvinceId(destId)
                                    .swapAProvinceId(targetId).swapASlotIndex(i)
                                    .swapBProvinceId(targetId).swapBSlotIndex(j)
                                    .score(score)
                                    .build();
                        }
                    }
                }
            }
        }

        // Fallback: qualsiasi scambio valido
        if (bestAction == null) {
            int destId = reachable.get(0);
            Province dest = state.getProvince(destId);
            List<Cube> track = dest.getPoliticalTrack();
            for (int i = 1; i < track.size(); i++) {
                for (int j = 1; j < track.size(); j++) {
                    if (i != j && track.get(i).isColored() && track.get(j).isColored()) {
                        return BotAction.builder()
                                .character(CharacterType.SPIA)
                                .actionType(actionType)
                                .moveToProvinceId(destId)
                                .swapAProvinceId(destId).swapASlotIndex(i)
                                .swapBProvinceId(destId).swapBSlotIndex(j)
                                .score(0.5)
                                .build();
                    }
                }
            }
        }

        return bestAction;
    }

    // ── Esercito ──────────────────────────────────────────────────────────────

    private BotAction evaluateEsercito(GameState state, int botIdx,
                                        CharacterType armyType, ActionType actionType) {
        GameCharacter army = state.getCharacter(armyType);
        Province current = state.getProvince(army.getCurrentProvinceId());

        List<Integer> reachable;
        if (armyType == CharacterType.FANTERIA) {
            reachable = new ArrayList<>(current.getAdjacentIds());
            reachable.addAll(current.getSeaAdjacentIds());
        } else {
            reachable = new ArrayList<>();
            for (int firstHop : current.getAdjacentIds()) {
                Province fp = state.getProvince(firstHop);
                for (int secondHop : fp.getAdjacentIds()) {
                    if (secondHop != army.getCurrentProvinceId()
                            && !reachable.contains(secondHop)) {
                        reachable.add(secondHop);
                    }
                }
                if (!reachable.contains(firstHop)) reachable.add(firstHop);
            }
        }

        if (reachable.isEmpty()) return null;

        BotAction bestAction = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (int destId : reachable) {
            Province dest = state.getProvince(destId);

            if (dest.isAtWar()) {
                double winProb = BotEvaluator.battleWinProbability(dest, botIdx, state, armyType);
                double score = winProb * (dest.getEconomicValue() * 3.0 + 5.0);
                if (winProb >= 0.7 && score > bestScore) {
                    bestScore = score;
                    bestAction = BotAction.builder()
                            .character(armyType).actionType(actionType)
                            .moveToProvinceId(destId).declareBattle(true)
                            .score(score).build();
                }
            } else {
                int needed = actionType == ActionType.POTENZIATA ? 2 : 1;
                if (dest.countInvasionCubes() < needed) continue;
                double score = BotEvaluator.provinceValue(dest, botIdx, state.getPlayers())
                        + dest.countInvasionCubes() * 2.0;
                if (score > bestScore) {
                    bestScore = score;
                    bestAction = BotAction.builder()
                            .character(armyType).actionType(actionType)
                            .moveToProvinceId(destId).declareBattle(false)
                            .score(score).build();
                }
            }
        }

        return bestAction;
    }
}