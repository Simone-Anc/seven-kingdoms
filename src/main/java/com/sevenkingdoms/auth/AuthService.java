package com.sevenkingdoms.auth;

import com.sevenkingdoms.exception.GameException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;

    // ── Registrazione ─────────────────────────────────────────────────────────

    public AuthResponse register(String nickname, String password) {
        if (nickname == null || nickname.trim().length() < 3) {
            throw GameException.invalidAction("Il nickname deve avere almeno 3 caratteri");
        }
        if (password == null || password.length() < 6) {
            throw GameException.invalidAction("La password deve avere almeno 6 caratteri");
        }
        if (userRepository.existsByNickname(nickname.trim())) {
            throw GameException.invalidAction("Nickname già in uso");
        }

        AppUser user = AppUser.create(nickname.trim(), passwordEncoder.encode(password));
        userRepository.save(user);

        return new AuthResponse(jwtService.generateToken(user), toProfile(user));
    }

    // ── Login ─────────────────────────────────────────────────────────────────

    public AuthResponse login(String nickname, String password) {
        AppUser user = userRepository.findByNickname(nickname)
                .orElseThrow(() -> GameException.invalidAction("Nickname o password errati"));

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw GameException.invalidAction("Nickname o password errati");
        }

        return new AuthResponse(jwtService.generateToken(user), toProfile(user));
    }

    // ── Profilo ───────────────────────────────────────────────────────────────

    public UserProfile getProfile(String userId) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> GameException.invalidAction("Utente non trovato"));
        return toProfile(user);
    }

    public AppUser getUserById(String userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> GameException.invalidAction("Utente non trovato"));
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private UserProfile toProfile(AppUser user) {
        return new UserProfile(
                user.getId(),
                user.getNickname(),
                user.getGamesPlayed(),
                user.getGamesWon(),
                user.getTotalScore(),
                user.getCreatedAt().toString()
        );
    }

    // ── Record di risposta ────────────────────────────────────────────────────

    public record AuthResponse(String token, UserProfile profile) {}

    public record UserProfile(
            String id,
            String nickname,
            int gamesPlayed,
            int gamesWon,
            int totalScore,
            String createdAt
    ) {}
}