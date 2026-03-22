package com.sevenkingdoms.service;

import com.sevenkingdoms.exception.GameException;
import com.sevenkingdoms.model.*;
import com.sevenkingdoms.model.enums.CharacterType;
import com.sevenkingdoms.model.enums.GamePhase;
import com.sevenkingdoms.model.enums.ProvinceStatus;
import com.sevenkingdoms.repository.GameRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Fase passiva step-by-step:
 *
 * STEP 1 — Re:        maggioranza → corona
 * STEP 2 — Papa:      maggioranza → Favore Papale
 * STEP 3 — Mercante:  maggioranza nella provincia del Mercante →
 *                     il giocatore sceglie dove avanzare l'eco
 * STEP 4 — Spia:      aggiunge cubetto invasione
 * STEP 5 — Armate:    risolve battaglie
 *
 * Il frontend chiama /passive/start per avviare la fase.
 * Quando arriva allo step del Mercante, il backend risponde con
 * passiveStep=MERCANTE_CHOOSE e la lista delle province selezionabili.
 * Il frontend mostra le province, il giocatore sceglie, e chiama
 * /passive/mercante-eco con la provincia scelta.
 * Poi il backend prosegue con gli step successivi.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PassivePhaseService {

    private final GameRepository gameRepository;
    private final NotificationService notificationService;

    // ── Avvia fase passiva (step 1-2, poi si ferma al Mercante) ──────────────

    public GameState resolvePassivePhase(String gameId) {
        GameState state = gameRepository.getOrThrow(gameId);
        if (state.getPhase() != GamePhase.PASSIVE_ACTIONS) {
            throw GameException.wrongPhase("Non siamo nella fase passiva");
        }

        state.addEvent("=== FASE PASSIVA ===");

        // Step 1 — Re
        passiveRe(state);

        // Step 2 — Papa
        passivePapa(state);

        // Step 3 — Mercante: richiede input giocatore
        int mercanteMajority = getMercanteChoice(state);
        if (mercanteMajority >= 0) {
            // C'è una maggioranza — calcola province disponibili e metti in attesa
            List<Integer> ecoTargets = getMercanteEcoTargets(state);
            state.setPendingMercantePlayerIndex(mercanteMajority);
            state.setPendingMercanteEcoTargets(ecoTargets);
            state.setPassiveSubStep("MERCANTE_CHOOSE");
            state.addEvent("💰 Mercante: " + state.getPlayers().get(mercanteMajority).getName()
                    + " deve scegliere dove avanzare l'economia");
        } else {
            // Nessuna maggioranza → salta il Mercante
            state.addEvent("💰 Mercante: nessuna maggioranza → eco invariata");
            continueAfterMercante(state);
        }

        GameState saved = gameRepository.save(state);
        notificationService.broadcastGameState(saved);
        return saved;
    }

    // ── Il giocatore sceglie la provincia per l'eco ───────────────────────────

    public GameState resolveMercanteEco(String gameId, String playerId, int targetProvinceId) {
        GameState state = gameRepository.getOrThrow(gameId);

        if (!"MERCANTE_CHOOSE".equals(state.getPassiveSubStep())) {
            throw GameException.wrongPhase("Non siamo nello step scelta eco del Mercante");
        }

        // Valida che sia il giocatore corretto (chi ha la maggioranza)
        int expectedPlayerIdx = state.getPendingMercantePlayerIndex();
        Player expectedPlayer = state.getPlayers().get(expectedPlayerIdx);
        if (!expectedPlayer.getId().equals(playerId)) {
            throw GameException.notYourTurn("Non è il tuo turno di scegliere: tocca a "
                    + expectedPlayer.getName());
        }

        // Valida che la provincia scelta sia valida
        if (!state.getPendingMercanteEcoTargets().contains(targetProvinceId)) {
            throw GameException.invalidAction("Provincia non valida per l'aumento economico");
        }

        // Applica l'aumento eco
        Province target = state.getProvince(targetProvinceId);
        if (target.isAtWar()) {
            throw GameException.invalidAction("Non puoi aumentare l'eco di una provincia in Guerra");
        }

        int oldValue = target.getEconomicValue();
        target.increaseEconomic();
        state.addEvent("💰 Mercante: " + expectedPlayer.getName()
                + " aumenta eco di " + target.getName()
                + " → " + target.getEconomicValue());

        // Reset pending state
        state.setPendingMercantePlayerIndex(-1);
        state.setPendingMercanteEcoTargets(new ArrayList<>());
        state.setPassiveSubStep(null);

        // Prosegui con Spia e Armate
        continueAfterMercante(state);

        GameState saved = gameRepository.save(state);
        notificationService.broadcastGameState(saved);
        return saved;
    }

    // ── Continua dopo il Mercante (Spia + Armate + Fine) ─────────────────────

    private void continueAfterMercante(GameState state) {
        // Step 4 — Spia
        passiveSpia(state);

        // Step 5 — Armate
        passiveArmate(state);

        state.addEvent("=== Fine Fase Passiva → Fine Turno ===");
        state.setPhase(GamePhase.END_TURN);
    }

    // ── Province disponibili per la scelta eco del Mercante ──────────────────
    public List<Integer> getMercanteEcoTargets(GameState state) {
        // Il Mercante può scegliere QUALSIASI provincia del regno
        return state.getProvinces().stream()
                .filter(p -> !p.isAtWar())
                .filter(p -> !p.isEconomicFullOfReligious())
                .map(Province::getId)
                .collect(Collectors.toList());
    }

    // ── Step 1: Re ────────────────────────────────────────────────────────────

    private void passiveRe(GameState state) {
        GameCharacter re = state.getCharacter(CharacterType.RE);
        Province province = state.getProvince(re.getCurrentProvinceId());

        int majority = getMajority(province, state.getPlayers().size());
        if (majority >= 0) {
            state.getPlayers().get(state.getFirstPlayerIndex()).setHasFirstPlayerToken(false);
            state.setFirstPlayerIndex(majority);
            state.getPlayers().get(majority).setHasFirstPlayerToken(true);
            state.addEvent("👑 Re: " + state.getPlayers().get(majority).getName()
                    + " ottiene la corona in " + province.getName());
        } else {
            state.addEvent("👑 Re: nessuna maggioranza in " + province.getName());
        }
    }

    // ── Step 2: Papa ──────────────────────────────────────────────────────────

    private void passivePapa(GameState state) {
        GameCharacter papa = state.getCharacter(CharacterType.PAPA);
        Province province = state.getProvince(papa.getCurrentProvinceId());

        int majority = getMajority(province, state.getPlayers().size());
        if (majority >= 0) {
            Player winner = state.getPlayers().get(majority);
            if (!winner.hasPapalFavor()) {
                winner.gainPapalFavor();
                state.addEvent("⛪ Papa: " + winner.getName()
                        + " ottiene un Favore Papale in " + province.getName());
            } else {
                state.addEvent("⛪ Papa: " + winner.getName()
                        + " ha già un Favore Papale → nessun guadagno");
            }
        } else {
            state.addEvent("⛪ Papa: nessuna maggioranza in " + province.getName());
        }
    }

    // ── Helper: chi ha la maggioranza nella provincia del Mercante ────────────

    private int getMercanteChoice(GameState state) {
        GameCharacter mercante = state.getCharacter(CharacterType.MERCANTE);
        Province province = state.getProvince(mercante.getCurrentProvinceId());
        if (province.isAtWar()) return -1;
        return getMajority(province, state.getPlayers().size());
    }

    // ── Step 4: Spia ──────────────────────────────────────────────────────────

    private void passiveSpia(GameState state) {
        GameCharacter spia = state.getCharacter(CharacterType.SPIA);
        Province province = state.getProvince(spia.getCurrentProvinceId());

        if (province.isPacified()) {
            state.addEvent("🕵️ Spia: nessun effetto (provincia " + province.getName() + " pacificata)");
            return;
        }

        List<Cube> track = province.getPoliticalTrack();

        for (int i = track.size() - 1; i >= 0; i--) {
            Cube c = track.get(i);
            if (c.isEmpty()) {
                track.set(i, Cube.invasion());
                state.addEvent("🕵️ Spia: cubetto invasione aggiunto in "
                        + province.getName() + " (slot " + i + ")");
                return;
            }
            if (c.isColored()) {
                int ownerIdx = c.getPlayerIndex();
                state.getPlayers().get(ownerIdx).returnCubeToReserve();
                track.set(i, Cube.invasion());
                province.setStatus(ProvinceStatus.GUERRA);
                state.addEvent("🕵️ Spia: GUERRA in " + province.getName()
                        + "! Cubetto di " + state.getPlayers().get(ownerIdx).getName()
                        + " rimosso (slot " + i + ")");
                return;
            }
        }
        state.addEvent("🕵️ Spia: nessuno slot disponibile in " + province.getName());
    }

    // ── Step 5: Armate ────────────────────────────────────────────────────────

    private void passiveArmate(GameState state) {
        for (CharacterType armyType : List.of(CharacterType.FANTERIA, CharacterType.CAVALLERIA)) {
            GameCharacter army = state.getCharacter(armyType);
            if (army.isDeclaredBattle()) {
                resolveBattle(state, army);
            }
        }
    }

    private void resolveBattle(GameState state, GameCharacter army) {
        Province province = state.getProvince(army.getCurrentProvinceId());
        if (!province.isAtWar()) return;

        int attackerIdx = getMajority(province, state.getPlayers().size());
        if (attackerIdx < 0) return;

        Player attacker = state.getPlayers().get(attackerIdx);
        int attackValue = province.countCubesOfPlayer(attackerIdx);

        if (attacker.isHasFirstPlayerToken()) { attackValue++; }
        if (army.getType() == CharacterType.FANTERIA) { attackValue++; }
        Optional<Cube> governor = province.getGovernor();
        if (governor.isPresent() && governor.get().isColored()
                && governor.get().getPlayerIndex() == attackerIdx) { attackValue++; }
        if (attacker.hasPapalFavor()) { attacker.spendPapalFavor(); attackValue++; }

        int invasionCubes = province.countInvasionCubes();
        boolean win = attackValue >= invasionCubes;

        state.addEvent("⚔️ Battaglia in " + province.getName() + ": "
                + attacker.getName() + " attacca " + attackValue + " vs " + invasionCubes);

        if (win) {
            List<Cube> track = province.getPoliticalTrack();
            for (int i = 0; i < track.size(); i++) {
                if (track.get(i).isInvasion()) track.set(i, Cube.empty());
            }
            track.set(0, Cube.ofPlayer(attackerIdx));
            attacker.takeCubeFromReserve();
            province.increaseEconomic();
            province.resolveWar();
            state.addEvent("✅ VITTORIA di " + attacker.getName() + " in " + province.getName());
        } else {
            province.addInvasionCube();
            state.addEvent("❌ SCONFITTA di " + attacker.getName() + " in " + province.getName());
        }
    }

    // ── Helper: maggioranza ───────────────────────────────────────────────────

    private int getMajority(Province province, int numPlayers) {
        int[] counts = new int[numPlayers];
        for (Cube c : province.getPoliticalTrack()) {
            if (c.isColored()) counts[c.getPlayerIndex()]++;
        }

        int maxCount = 0;
        int winner = -1;
        boolean tie = false;

        for (int i = 0; i < numPlayers; i++) {
            if (counts[i] > maxCount) {
                maxCount = counts[i];
                winner = i;
                tie = false;
            } else if (counts[i] == maxCount && maxCount > 0) {
                tie = true;
            }
        }

        if (maxCount == 0) return -1;
        if (tie) {
            Cube gov = province.getPoliticalTrack().get(0);
            if (gov.isColored()) return gov.getPlayerIndex();
            return -1;
        }
        return winner;
    }
}