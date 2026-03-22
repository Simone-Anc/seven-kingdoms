package com.sevenkingdoms.service;

import com.sevenkingdoms.model.GameState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Invia lo stato aggiornato a tutti i client connessi alla partita via WebSocket.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Broadcast dello stato a tutti i giocatori della partita.
     * Il frontend React si iscrive a /topic/game/{gameId}
     */
    public void broadcastGameState(GameState state) {
        String destination = "/topic/game/" + state.getGameId();
        messagingTemplate.convertAndSend(destination, state);
        log.debug("Stato inviato su {}: fase={}", destination, state.getPhase());
    }

    /**
     * Messaggio di errore a un singolo giocatore.
     * Il frontend si iscrive a /user/queue/errors
     */
    public void sendError(String sessionId, String code, String message) {
        messagingTemplate.convertAndSendToUser(
                sessionId,
                "/queue/errors",
                Map.of("code", code, "message", message)
        );
    }
}
