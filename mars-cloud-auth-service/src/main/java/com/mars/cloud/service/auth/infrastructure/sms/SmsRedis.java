package com.mars.cloud.service.auth.infrastructure.sms;

import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public final class SmsRedis {
    private final StringRedisTemplate redis;
    public SmsRedis(StringRedisTemplate redis) { this.redis = redis; }
    public long run(String source, List<String> keys, String... args) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(source, Long.class);
        Long value = redis.execute(script, keys, (Object[]) args);
        if (value == null) throw new IllegalStateException("Redis script returned no result");
        return value;
    }
    public StringRedisTemplate template() { return redis; }
}
