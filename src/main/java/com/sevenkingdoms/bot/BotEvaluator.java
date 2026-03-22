package com.sevenkingdoms.bot;

import com.sevenkingdoms.model.*;
import com.sevenkingdoms.model.enums.CharacterType;

import java.util.List;
import java.util.Optional;

public class BotEvaluator {

    // ── Valore di una provincia per il bot ────────────────────────────────────

    public static double provinceValue(Province province, int botPlayerIndex,
                                       List<Player> players) {
        double score = 0;

        score += province.getEconomicValue() * 2.0;
        score -= province.getReligiousMarkers() * 1.5;

        long botCubes = province.getPoliticalTrack().stream()
                .filter(c -> c.isColored() && c.getPlayerIndex() == botPlayerIndex)
                .count();
        score += botCubes * 1.5;

        // Governatore
        Cube gov = province.getPoliticalTrack().isEmpty()
                ? null : province.getPoliticalTrack().get(0);
        if (gov != null && gov.isColored() && gov.getPlayerIndex() == botPlayerIndex) {
            score += 4.0;
        }

        long invasionCubes = province.getPoliticalTrack().stream()
                .filter(Cube::isInvasion).count();
        score -= invasionCubes * 2.0;

        if (province.isPacified()) score += 3.0;
        if (province.isAtWar()) score -= 4.0;

        // Bonus se la provincia non ha ancora un Governatore colorato
        boolean hasGovernor = gov != null && gov.isColored();
        if (!hasGovernor) score += 3.0;

        return score;
    }

    // ── Valore setup: preferisce province senza Governatore ──────────────────

    public static double setupProvinceValue(Province province, int botPlayerIndex,
                                             List<Player> players) {
        double score = provinceValue(province, botPlayerIndex, players);

        // Forte priorità a province senza Governatore
        Cube gov = province.getPoliticalTrack().isEmpty()
                ? null : province.getPoliticalTrack().get(0);
        boolean hasGovernor = gov != null && gov.isColored();
        if (!hasGovernor) {
            score += 8.0; // molto più attraente diventare primo Governatore
        }

        // Preferisce province con eco alto (valgono di più come Governatore)
        score += province.getEconomicValue() * 1.5;

        return score;
    }

    // ── Stima punti vittoria correnti ─────────────────────────────────────────

    public static double estimateVictoryPoints(GameState state, int botPlayerIndex) {
        double vp = 0;
        List<Player> players = state.getPlayers();

        for (Province province : state.getProvinces()) {
            int governorVp = Math.max(1, 5 - province.getReligiousMarkers());
            int normalVp   = province.getEconomicValue();

            for (int i = 0; i < province.getPoliticalTrack().size(); i++) {
                Cube c = province.getPoliticalTrack().get(i);
                if (c.isColored() && c.getPlayerIndex() == botPlayerIndex) {
                    vp += (i == 0) ? governorVp : normalVp;
                }
            }

            // Vescovo
            if (province.getCathedralPlayerIndex() == botPlayerIndex) {
                vp += 2.0 + province.getReligiousMarkers() * 2.0;
            }
        }

        int marketCubes = state.getMarketCubes().get(botPlayerIndex);
        vp += marketCubes * 0.5;

        Player bot = players.get(botPlayerIndex);
        vp += bot.getPapalFavors() * 2;
        if (bot.isHasFirstPlayerToken()) vp += 2;

        return vp;
    }

    // ── Valore del vescovo in una provincia ───────────────────────────────────

    public static double bishopValue(Province province) {
        // Vale 2 + 2*segnalini religiosi già presenti
        return 2.0 + province.getReligiousMarkers() * 2.0;
    }

    // ── Urgenza di piazzare il vescovo ────────────────────────────────────────
    // Alta se ci sono >=2 segnalini religiosi e la cattedrale è libera

    public static double bishopUrgency(Province province) {
        if (province.getCathedralPlayerIndex() >= 0) return 0; // già occupata
        int markers = province.getReligiousMarkers();
        if (markers == 0) return 0;
        // Con 1 segnalino vale 4pv, con 2 vale 6pv, con 3 vale 8pv
        return markers * 3.0;
    }

    // ── Priorità personaggio per turno ────────────────────────────────────────

    public static double characterPriorityMultiplier(CharacterType charType, int turnNumber) {
        return switch (charType) {
            // Primi 3 turni: Re è molto importante per conquistare province
            case RE         -> turnNumber <= 3 ? 2.0 : 1.0;
            // Dal turno 4: Papa diventa importante se ci sono segnalini
            case PAPA       -> turnNumber <= 2 ? 0.7 : 1.2;
            // Mercante sempre moderatamente utile
            case MERCANTE   -> 0.8;
            // Spia utile a metà partita
            case SPIA       -> turnNumber <= 2 ? 0.5 : 1.0;
            // Esercito utile solo se ci sono invasioni
            case FANTERIA, CAVALLERIA -> 0.9;
        };
    }

    // ── Probabilità di vincere una battaglia ──────────────────────────────────

    public static double battleWinProbability(Province province, int botPlayerIndex,
                                               GameState state, CharacterType armyType) {
        int botCubes = province.countCubesOfPlayer(botPlayerIndex);
        int invasionCubes = province.countInvasionCubes();

        int attackValue = botCubes;
        Player bot = state.getPlayers().get(botPlayerIndex);
        if (bot.isHasFirstPlayerToken()) attackValue++;
        if (armyType == CharacterType.FANTERIA) attackValue++;
        if (bot.hasPapalFavor()) attackValue++;

        Cube gov = province.getPoliticalTrack().isEmpty()
                ? null : province.getPoliticalTrack().get(0);
        if (gov != null && gov.isColored() && gov.getPlayerIndex() == botPlayerIndex) {
            attackValue++;
        }

        if (invasionCubes == 0) return 0.0;
        return attackValue >= invasionCubes ? 1.0 : (double) attackValue / invasionCubes;
    }
}