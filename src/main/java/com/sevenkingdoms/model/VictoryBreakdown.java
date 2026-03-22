package com.sevenkingdoms.model;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class VictoryBreakdown {
    private String playerName;
    private String playerColor;
    private int totalPoints;

    private int pointsFromGovernors;
    private int pointsFromCubes;
    private int pointsFromMarket;
    private int pointsFromBishops;
    private int pointsFromFirstPlayer;
    private int pointsFromPapalFavors;

    private List<String> details; // righe descrittive

    public static VictoryBreakdown empty(Player player) {
        return VictoryBreakdown.builder()
                .playerName(player.getName())
                .playerColor(player.getColor())
                .totalPoints(0)
                .pointsFromGovernors(0)
                .pointsFromCubes(0)
                .pointsFromMarket(0)
                .pointsFromBishops(0)
                .pointsFromFirstPlayer(0)
                .pointsFromPapalFavors(0)
                .details(new ArrayList<>())
                .build();
    }
}