package com.mars.cloud.service.gateway.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.util.regex.Pattern;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Mono;

/** Reads the shared session revocation key without blocking a Gateway request thread. */
public final class GatewaySessionRevocation {
    public static final String KEY_PREFIX = "mars:auth:revoked:sid:";
    private static final Pattern SID = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private static final Duration CACHE_TTL = Duration.ofSeconds(5);
    private static final long CACHE_TTL_NANOS = CACHE_TTL.toNanos();

    private final ReactiveStringRedisTemplate redis;
    private final Ticker ticker;
    private final Cache<String, CachedResult> cache;

    public GatewaySessionRevocation(ReactiveStringRedisTemplate redis, Ticker ticker) {
        this.redis = redis;
        this.ticker = ticker;
        this.cache = Caffeine.newBuilder().maximumSize(100_000)
                .expireAfterWrite(CACHE_TTL).ticker(ticker).build();
    }

    public static boolean validSid(String sid) {
        return sid != null && sid.length() <= 128 && SID.matcher(sid).matches();
    }

    public Mono<Boolean> isRevoked(String sid) {
        if (!validSid(sid)) {
            return Mono.error(new IllegalArgumentException("Invalid session ID"));
        }
        return Mono.defer(() -> {
            Boolean cached = cachedValue(sid);
            if (cached != null) {
                return Mono.just(cached);
            }
            long readStarted = ticker.read();
            return redis.hasKey(KEY_PREFIX + sid)
                    .timeout(Duration.ofSeconds(2))
                    .switchIfEmpty(Mono.error(new StoreUnavailableException()))
                    .doOnNext(value -> cache.put(sid, new CachedResult(value, readStarted)))
                    .onErrorResume(error -> {
                        Boolean fallback = cachedValue(sid);
                        return fallback != null ? Mono.just(fallback) : Mono.error(new StoreUnavailableException());
                    });
        });
    }

    private Boolean cachedValue(String sid) {
        CachedResult result = cache.getIfPresent(sid);
        if (result == null) return null;
        long age = ticker.read() - result.readStarted();
        if (age < 0 || age >= CACHE_TTL_NANOS) {
            cache.asMap().remove(sid, result);
            return null;
        }
        return result.revoked();
    }

    private record CachedResult(boolean revoked, long readStarted) { }

    /** No Redis address or credentials enter the response or warning log. */
    public static final class StoreUnavailableException extends RuntimeException {
        public StoreUnavailableException() {
            super("Session revocation store unavailable");
        }
    }
}
