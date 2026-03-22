package com.sevenkingdoms.auth;

import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Repository in-memory degli utenti.
 * Da sostituire con JPA/PostgreSQL quando disponibile.
 */
@Repository
public class UserRepository {

    // id → user
    private final Map<String, AppUser> byId       = new ConcurrentHashMap<>();
    // nickname (lowercase) → user
    private final Map<String, AppUser> byNickname = new ConcurrentHashMap<>();

    public AppUser save(AppUser user) {
        byId.put(user.getId(), user);
        byNickname.put(user.getNickname().toLowerCase(), user);
        return user;
    }

    public Optional<AppUser> findById(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public Optional<AppUser> findByNickname(String nickname) {
        return Optional.ofNullable(byNickname.get(nickname.toLowerCase()));
    }

    public boolean existsByNickname(String nickname) {
        return byNickname.containsKey(nickname.toLowerCase());
    }

    public Collection<AppUser> findAll() {
        return byId.values();
    }
}