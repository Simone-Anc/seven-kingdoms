package com.sevenkingdoms.auth;

import com.sevenkingdoms.auth.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final UserRepository userRepository;

    // ── Registrazione ─────────────────────────────────────────────────────────

    @PostMapping("/register")
    public ResponseEntity<AuthService.AuthResponse> register(@RequestBody AuthRequest request) {
        return ResponseEntity.ok(authService.register(request.nickname(), request.password()));
    }

    // ── Login ─────────────────────────────────────────────────────────────────

    @PostMapping("/login")
    public ResponseEntity<AuthService.AuthResponse> login(@RequestBody AuthRequest request) {
        return ResponseEntity.ok(authService.login(request.nickname(), request.password()));
    }

    // ── Profilo corrente ──────────────────────────────────────────────────────

    @GetMapping("/me")
    public ResponseEntity<AuthService.UserProfile> me(@AuthenticationPrincipal AppUser user) {
        return ResponseEntity.ok(authService.getProfile(user.getId()));
    }

    // ── Admin: lista utenti ──────────────────────────────────────────────────

    @GetMapping("/users")
    public ResponseEntity<?> listUsers() {
        var users = userRepository.findAll().stream()
            .map(u -> new java.util.LinkedHashMap<String, Object>() {{
                put("nickname", u.getNickname());
                put("gamesPlayed", u.getGamesPlayed());
                put("gamesWon", u.getGamesWon());
                put("createdAt", u.getCreatedAt().toString());
            }})
            .toList();
        return ResponseEntity.ok(users);
    }

    // ── Request ───────────────────────────────────────────────────────────────

    public record AuthRequest(String nickname, String password) {}
}