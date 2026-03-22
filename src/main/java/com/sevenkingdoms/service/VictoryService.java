package com.sevenkingdoms.service;

import com.sevenkingdoms.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class VictoryService {

    public void calculateScores(GameState state) {
        List<Player> players = state.getPlayers();
        players.forEach(p -> p.setVictoryPoints(0));

        List<VictoryBreakdown> breakdowns = new ArrayList<>();

        for (Player player : players) {
            int idx = player.getColorIndex();
            VictoryBreakdown bd = VictoryBreakdown.empty(player);

            // ── 1. Cubetti nelle province ─────────────────────────────────────
            for (Province province : state.getProvinces()) {
                int governorVp = getGovernorValue(province);
                int normalVp   = province.getEconomicValue();

                for (int i = 0; i < province.getPoliticalTrack().size(); i++) {
                    Cube cube = province.getPoliticalTrack().get(i);
                    if (!cube.isColored() || cube.getPlayerIndex() != idx) continue;

                    if (i == 0) {
                        // Governatore
                        bd.setPointsFromGovernors(bd.getPointsFromGovernors() + governorVp);
                        bd.getDetails().add("Governatore di " + province.getName()
                                + ": +" + governorVp + " pv (eco=" + governorVp + ")");
                    } else {
                        bd.setPointsFromCubes(bd.getPointsFromCubes() + normalVp);
                        bd.getDetails().add("Cubetto in " + province.getName()
                                + ": +" + normalVp + " pv (eco=" + normalVp + ")");
                    }
                }
            }

            // ── 2. Mercato ────────────────────────────────────────────────────
            assignMarketPoints(state, breakdowns, players);
            // (assegnato dopo, vedi metodo separato)

            // ── 3. Primo giocatore ────────────────────────────────────────────
            if (player.isHasFirstPlayerToken()) {
                bd.setPointsFromFirstPlayer(2);
                bd.getDetails().add("Segnalino Primo Giocatore: +2 pv");
            }

            // ── 4. Favori papali ──────────────────────────────────────────────
            int papalPv = player.getPapalFavors() * 2;
            bd.setPointsFromPapalFavors(papalPv);
            if (papalPv > 0) {
                bd.getDetails().add("Favori Papali (" + player.getPapalFavors()
                        + " x 2): +" + papalPv + " pv");
            }

            // ── 5. Vescovi ────────────────────────────────────────────────────
            for (Province province : state.getProvinces()) {
                if (province.getCathedralPlayerIndex() == idx) {
                    int bishopPv = 2 + province.getReligiousMarkers() * 2;
                    bd.setPointsFromBishops(bd.getPointsFromBishops() + bishopPv);
                    bd.getDetails().add("Vescovo in " + province.getName()
                            + " (2 + " + province.getReligiousMarkers()
                            + " segnalini x 2): +" + bishopPv + " pv");
                }
            }

            breakdowns.add(bd);
        }

        // Assegna punti mercato
        assignMarketPoints(state, breakdowns, players);

        // Calcola totale e aggiorna Player
        for (int i = 0; i < players.size(); i++) {
            VictoryBreakdown bd = breakdowns.get(i);
            int total = bd.getPointsFromGovernors()
                    + bd.getPointsFromCubes()
                    + bd.getPointsFromMarket()
                    + bd.getPointsFromFirstPlayer()
                    + bd.getPointsFromPapalFavors()
                    + bd.getPointsFromBishops();
            bd.setTotalPoints(total);
            players.get(i).setVictoryPoints(total);
        }

        // Salva i breakdown nello stato
        state.setVictoryBreakdowns(breakdowns);

        // Log risultati
        breakdowns.stream()
                .sorted(Comparator.comparingInt(VictoryBreakdown::getTotalPoints).reversed())
                .forEach(bd -> {
                    log.info("{}: {} pv", bd.getPlayerName(), bd.getTotalPoints());
                    bd.getDetails().forEach(d -> log.info("  {}", d));
                    state.addEvent(bd.getPlayerName() + ": " + bd.getTotalPoints() + " PV");
                });
    }

    private int getGovernorValue(Province province) {
        return Math.max(1, 5 - province.getReligiousMarkers());
    }

    private void assignMarketPoints(GameState state,
                                     List<VictoryBreakdown> breakdowns,
                                     List<Player> players) {
        List<Integer> marketCubes = state.getMarketCubes();
        int[] points = {10, 5, 2};

        // Ordina per cubetti (decrescente)
        List<Integer> sorted = players.stream()
                .map(Player::getColorIndex)
                .sorted((a, b) -> marketCubes.get(b) - marketCubes.get(a))
                .collect(Collectors.toList());

        int rank = 0;
        int prevCount = -1;
        int sharedCount = 0;
        int sharedPoints = 0;
        List<Integer> sharedPlayers = new ArrayList<>();

        for (int idx : sorted) {
            int cubes = marketCubes.get(idx);
            if (cubes == 0) break;

            if (cubes == prevCount) {
                // Parità: condividono il punteggio dello stesso rank
                sharedPlayers.add(idx);
                if (rank - 1 < points.length) sharedPoints = points[rank - 1];
            } else {
                // Assegna ai precedenti in parità
                if (!sharedPlayers.isEmpty() && sharedCount > 1) {
                    for (int pi : sharedPlayers) {
                        if (pi < breakdowns.size()) {
                            int pv = sharedPoints / sharedCount;
                            breakdowns.get(pi).setPointsFromMarket(pv);
                            breakdowns.get(pi).getDetails().add("Mercato (parità " + rank
                                    + "° posto): +" + pv + " pv");
                        }
                    }
                }

                sharedPlayers = new ArrayList<>(List.of(idx));
                sharedCount = 1;
                sharedPoints = rank < points.length ? points[rank] : 0;
                prevCount = cubes;
                rank++;

                if (sharedPoints > 0 && idx < breakdowns.size()) {
                    breakdowns.get(idx).setPointsFromMarket(sharedPoints);
                    breakdowns.get(idx).getDetails().add("Mercato (" + rank
                            + "° posto, " + cubes + " cubetti): +" + sharedPoints + " pv");
                }
            }
        }
    }
}