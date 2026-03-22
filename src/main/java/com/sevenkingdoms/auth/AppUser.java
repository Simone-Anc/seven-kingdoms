package com.sevenkingdoms.auth;

import lombok.Data;
import lombok.Builder;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AppUser {

    private String id;
    private String nickname;
    private String passwordHash; // BCrypt hash

    private int gamesPlayed;
    private int gamesWon;
    private int totalScore;

    private LocalDateTime createdAt;

    public static AppUser create(String nickname, String passwordHash) {
        return AppUser.builder()
                .id(UUID.randomUUID().toString())
                .nickname(nickname)
                .passwordHash(passwordHash)
                .gamesPlayed(0)
                .gamesWon(0)
                .totalScore(0)
                .createdAt(LocalDateTime.now())
                .build();
    }

    public void recordGame(boolean won, int score) {
        gamesPlayed++;
        if (won) gamesWon++;
        totalScore += score;
    }
}