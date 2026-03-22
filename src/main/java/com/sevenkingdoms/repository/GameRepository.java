package com.sevenkingdoms.repository;

import com.sevenkingdoms.exception.GameException;
import com.sevenkingdoms.model.GameState;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Store in-memory delle partite attive.
 * In futuro sostituibile con Redis o PostgreSQL.
 */
@Repository
public class GameRepository {

    private final Map<String, GameState> games = new ConcurrentHashMap<>();

    public GameState save(GameState game) {
        // Ricalcola lo status di tutte le province prima di salvare
        game.refreshAllProvinceStatuses();
        games.put(game.getGameId(), game);
        return game;
    }

    public Optional<GameState> findById(String gameId) {
        return Optional.ofNullable(games.get(gameId));
    }

    public GameState getOrThrow(String gameId) {
        return findById(gameId)
                .orElseThrow(() -> GameException.notFound("Partita non trovata: " + gameId));
    }

    public void delete(String gameId) {
        games.remove(gameId);
    }
}