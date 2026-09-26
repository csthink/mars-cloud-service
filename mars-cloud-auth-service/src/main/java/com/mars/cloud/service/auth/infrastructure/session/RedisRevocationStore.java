package com.mars.cloud.service.auth.infrastructure.session;

import com.mars.cloud.service.auth.domain.ClientPolicy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Redis marker shared with the gateway: key mars:auth:revoked:sid:&lt;sid&gt;, value 1, kept for the access token lifetime. */
@Component
public final class RedisRevocationStore implements RevocationStore {
    private final StringRedisTemplate redis;

    public RedisRevocationStore(StringRedisTemplate redis) {
        if (ClientPolicy.ACCESS_TOKEN_TTL.compareTo(TTL) > 0) throw new IllegalStateException("Revocation markers must outlive access tokens");
        this.redis = redis;
    }

    @Override
    public void revoke(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("Session identifier is required");
        redis.opsForValue().set(KEY_PREFIX + sessionId, "1", TTL);
    }
}
