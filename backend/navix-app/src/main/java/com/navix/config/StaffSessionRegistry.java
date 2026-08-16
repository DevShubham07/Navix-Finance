package com.navix.config;

import com.navix.iam.entity.StaffUser;
import com.navix.iam.repository.StaffUserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * The one-live-session-per-staffer check, on the request path. Kept behind a small component (not
 * inlined in {@link JwtAuthFilter}) so the filter doesn't need a repository dependency for anything
 * else it does.
 *
 * <p>Cached because this runs on EVERY authenticated staff request, including the 8-second queue
 * polls from every open tab — an uncached lookup would add one row read per poll per tab. 30s TTL
 * (product decision): a superseded tab can keep working for up to 30s after being replaced, which
 * is an acceptable trade for a poll-heavy console. No external cache dependency (Caffeine etc. is
 * not on this module's classpath) — a plain map with a manual expiry is enough at this scale.
 *
 * <p>{@link #invalidate} must be called by {@code AuthController} right after a forced login and
 * after logout, or the cached stale sid delays the kick-out by the TTL.
 */
@Component
public class StaffSessionRegistry {

    private static final Duration TTL = Duration.ofSeconds(30);

    private final StaffUserRepository staffRepository;
    private final Map<Long, CachedSession> cache = new ConcurrentHashMap<>();

    private record CachedSession(String sessionId, Instant expiresAt) {
    }

    public StaffSessionRegistry(StaffUserRepository staffRepository) {
        this.staffRepository = staffRepository;
    }

    /**
     * True when {@code sessionId} is the staffer's CURRENT session. A token with no {@code sid}
     * claim (minted before this feature shipped) is grandfathered as current, so deploying this
     * change never signs anyone out on its own — only the staffer's NEXT login starts enforcing it.
     */
    public boolean isCurrent(String staffId, String sessionId) {
        if (sessionId == null) {
            return true;
        }
        Long id = parse(staffId);
        if (id == null) {
            return true;
        }
        CachedSession cached = cache.get(id);
        String current;
        if (cached != null && Instant.now().isBefore(cached.expiresAt())) {
            current = cached.sessionId();
        } else {
            current = staffRepository.findById(id).map(StaffUser::getActiveSessionId).orElse(null);
            cache.put(id, new CachedSession(current, Instant.now().plus(TTL)));
        }
        return current == null || current.equals(sessionId);
    }

    /** Evict the cached session id for {@code staffId} so the next request re-reads the database. */
    public void invalidate(Long staffId) {
        if (staffId != null) {
            cache.remove(staffId);
        }
    }

    private static Long parse(String id) {
        try {
            return Long.valueOf(id);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
