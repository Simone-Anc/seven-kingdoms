package com.sevenkingdoms.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(GameException.class)
    public ResponseEntity<Map<String, String>> handleGameException(GameException ex) {
        HttpStatus status = switch (ex.getCode()) {
            case "NOT_FOUND"     -> HttpStatus.NOT_FOUND;
            case "NOT_YOUR_TURN",
                 "WRONG_PHASE",
                 "INVALID_ACTION" -> HttpStatus.CONFLICT;
            default              -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status)
                .body(Map.of("code", ex.getCode(), "message", ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGeneric(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("code", "INTERNAL_ERROR", "message", ex.getMessage()));
    }
}
