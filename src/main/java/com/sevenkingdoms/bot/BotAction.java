package com.sevenkingdoms.bot;

import com.sevenkingdoms.model.enums.ActionType;
import com.sevenkingdoms.model.enums.CharacterType;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class BotAction {

    private CharacterType character;
    private ActionType actionType;
    private int moveToProvinceId;
    private double score;

    // Re
    private List<CubePlacement> cubePlacements;

    // Papa
    private int religiousTargetProvinceId;
    private Integer cathedralProvinceId;

    // Spia
    private Integer swapAProvinceId;
    private Integer swapASlotIndex;
    private Integer swapBProvinceId;
    private Integer swapBSlotIndex;

    // Esercito
    private boolean declareBattle;

    // Fase passiva Mercante
    private Integer mercanteEcoProvinceId;

    @Data
    @Builder
    public static class CubePlacement {
        private int provinceId;
        private int playerIndex;
    }
}