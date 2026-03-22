package com.sevenkingdoms.exception;

public class GameException extends RuntimeException {

    private final String code;

    public GameException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    // Shortcut factories
    public static GameException invalidAction(String msg) {
        return new GameException("INVALID_ACTION", msg);
    }

    public static GameException notFound(String msg) {
        return new GameException("NOT_FOUND", msg);
    }

    public static GameException notYourTurn(String msg) {
        return new GameException("NOT_YOUR_TURN", msg);
    }

    public static GameException wrongPhase(String msg) {
        return new GameException("WRONG_PHASE", msg);
    }
}
