package com.sevenkingdoms.model;

import com.sevenkingdoms.model.enums.CharacterType;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GameCharacter {

    private CharacterType type;
    private int currentProvinceId;   // Provincia in cui si trova il personaggio
    private boolean usedThisTurn;    // Già utilizzato nel turno corrente
    private boolean declaredBattle;  // Fanteria/Cavalleria ha dichiarato battaglia

    public static GameCharacter create(CharacterType type, int startProvinceId) {
        return GameCharacter.builder()
                .type(type)
                .currentProvinceId(startProvinceId)
                .usedThisTurn(false)
                .declaredBattle(false)
                .build();
    }

    public void moveTo(int provinceId) {
        this.currentProvinceId = provinceId;
    }

    public void markUsed() {
        this.usedThisTurn = true;
    }

    public void resetForNewTurn() {
        this.usedThisTurn = false;
        this.declaredBattle = false;
    }

    public boolean isArmy() {
        return type == CharacterType.FANTERIA || type == CharacterType.CAVALLERIA;
    }
}
