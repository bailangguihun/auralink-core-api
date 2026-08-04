package com.auralink.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

@Service
public class UploadSessionService {

    private static final Duration SESSION_TTL = Duration.ofMinutes(30);

    private final Map<String, SessionData> sessionStore = new ConcurrentHashMap<>();

    public void save(String sessionId, String filepath) {
        cleanupExpired();
        sessionStore.put(sessionId, new SessionData(filepath, Instant.now()));
    }

    public String get(String sessionId) {
        cleanupExpired();
        SessionData data = sessionStore.get(sessionId);
        if (data == null) {
            return null;
        }
        return data.filepath();
    }

    private void cleanupExpired() {
        Instant now = Instant.now();
        sessionStore.entrySet().removeIf(entry -> Duration.between(entry.getValue().createdAt(), now).compareTo(SESSION_TTL) > 0);
    }

    private record SessionData(String filepath, Instant createdAt) {
    }
}
