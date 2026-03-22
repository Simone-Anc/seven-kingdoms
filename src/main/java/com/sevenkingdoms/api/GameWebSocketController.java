package com.sevenkingdoms.api;

import com.sevenkingdoms.model.GameState;
import com.sevenkingdoms.model.enums.ActionType;
import com.sevenkingdoms.service.ReService;
import com.sevenkingdoms.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.Map;

/**
 * Riceve le azioni dei giocatori via WebSocket STOMP.
 *
 * Il frontend React pubblica su:
 *   /app/game/{gameId}/action/re
 *   /app/game/{gameId}/action/papa
 *   ecc.
 *
 * Lo stato aggiornato viene inviato a tutti su:
 *   /topic/game/{gameId}
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class GameWebSocketController {

    private final ReService reService;
    private final NotificationService notificationService;

    @MessageMapping("/game/{gameId}/action/re")
    public void handleReAction(
            @DestinationVariable String gameId,
            @Payload Map<String, Object> payload) {
        try {
            String playerId       = (String) payload.get("playerId");
            String actionTypeStr  = (String) payload.get("actionType");
            int moveToProvinceId  = (int) payload.getOrDefault("moveToProvinceId", -1);

            @SuppressWarnings("unchecked")
            List<Map<String, Integer>> rawPlacements =
                    (List<Map<String, Integer>>) payload.get("cubePlacements");

            List<ReService.CubePlacementRequest> placements = rawPlacements == null ? List.of() :
                    rawPlacements.stream()
                            .map(m -> new ReService.CubePlacementRequest(
                                    m.get("provinceId"), m.get("playerIndex")))
                            .toList();

            reService.executeAction(gameId, playerId,
                    ActionType.valueOf(actionTypeStr),
                    moveToProvinceId, placements);

        } catch (Exception e) {
            log.error("Errore azione Re su partita {}: {}", gameId, e.getMessage());
            // L'errore verrà gestito dal GlobalExceptionHandler lato REST
            // Per WS mandiamo un messaggio di errore al topic
            notificationService.broadcastGameState(
                    reService.toString() != null ? null : null // placeholder
            );
        }
    }

    // Placeholder per le altre azioni — da implementare progressivamente
    @MessageMapping("/game/{gameId}/action/papa")
    public void handlePapaAction(@DestinationVariable String gameId,
                                  @Payload Map<String, Object> payload) {
        log.info("Papa action ricevuta per partita {}", gameId);
        // TODO: PapaService.executeAction(...)
    }

    @MessageMapping("/game/{gameId}/action/mercante")
    public void handleMercanteAction(@DestinationVariable String gameId,
                                      @Payload Map<String, Object> payload) {
        log.info("Mercante action ricevuta per partita {}", gameId);
        // TODO: MercanteService.executeAction(...)
    }

    @MessageMapping("/game/{gameId}/action/spia")
    public void handleSpiaAction(@DestinationVariable String gameId,
                                  @Payload Map<String, Object> payload) {
        log.info("Spia action ricevuta per partita {}", gameId);
        // TODO: SpiaService.executeAction(...)
    }

    @MessageMapping("/game/{gameId}/action/esercito")
    public void handleEsercitoAction(@DestinationVariable String gameId,
                                      @Payload Map<String, Object> payload) {
        log.info("Esercito action ricevuta per partita {}", gameId);
        // TODO: EsercitoService.executeAction(...)
    }
}
