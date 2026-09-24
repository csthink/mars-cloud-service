package com.mars.cloud.service.gateway.security;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GatewaySessionRevocationTest {
    @Test
    void cachedNegativeResultExpiresAtFiveSecondsAndReadsRedisAgain() {
        var redis = mock(ReactiveStringRedisTemplate.class);
        var now = new AtomicLong();
        var revoked = new AtomicBoolean();
        when(redis.hasKey(anyString())).thenAnswer(call -> Mono.defer(() -> Mono.just(revoked.get())));
        var checker = new GatewaySessionRevocation(redis, now::get);
        String sid = "76e253e4-a65b-427f-a40d-3db52b26b461";

        Mono<Boolean> first = checker.isRevoked(sid);
        verifyNoInteractions(redis);
        assertThat(first.block()).isFalse();
        revoked.set(true);
        now.set(Duration.ofSeconds(5).toNanos() - 1);
        assertThat(checker.isRevoked(sid).block()).isFalse();
        verify(redis).hasKey(GatewaySessionRevocation.KEY_PREFIX + sid);

        now.incrementAndGet();
        assertThat(checker.isRevoked(sid).block()).isTrue();
        verify(redis, times(2)).hasKey(GatewaySessionRevocation.KEY_PREFIX + sid);
    }

    @Test
    void unavailableRedisUsesOnlyUnexpiredPositiveOrNegativeCache() {
        var redis = mock(ReactiveStringRedisTemplate.class);
        var now = new AtomicLong();
        var available = new AtomicBoolean(true);
        var revoked = new AtomicBoolean(true);
        when(redis.hasKey(anyString())).thenAnswer(call -> Mono.defer(() -> available.get()
                ? Mono.just(revoked.get()) : Mono.error(new IllegalStateException("connection details"))));
        var checker = new GatewaySessionRevocation(redis, now::get);

        assertThat(checker.isRevoked("session-a").block()).isTrue();
        available.set(false);
        assertThat(checker.isRevoked("session-a").block()).isTrue();
        assertThatThrownBy(() -> checker.isRevoked("session-b").block())
                .isInstanceOf(GatewaySessionRevocation.StoreUnavailableException.class)
                .hasMessageNotContaining("connection details");

        now.set(Duration.ofSeconds(5).toNanos());
        assertThatThrownBy(() -> checker.isRevoked("session-a").block())
                .isInstanceOf(GatewaySessionRevocation.StoreUnavailableException.class);
        available.set(true);
        revoked.set(false);
        assertThat(checker.isRevoked("session-a").block()).isFalse();
        available.set(false);
        assertThat(checker.isRevoked("session-a").block()).isFalse();
    }

    @Test
    void slowReadDoesNotExtendCacheBeyondFiveSecondsFromQueryStart() {
        var redis = mock(ReactiveStringRedisTemplate.class);
        var now = new AtomicLong();
        when(redis.hasKey(anyString())).thenAnswer(call -> Mono.defer(() -> {
            now.set(Duration.ofSeconds(4).toNanos());
            return Mono.just(false);
        }));
        var checker = new GatewaySessionRevocation(redis, now::get);

        assertThat(checker.isRevoked("session-a").block()).isFalse();
        now.set(Duration.ofSeconds(5).toNanos());
        assertThat(checker.isRevoked("session-a").block()).isFalse();
        verify(redis, times(2)).hasKey(GatewaySessionRevocation.KEY_PREFIX + "session-a");
    }

    @Test
    void sessionIdMustBeBoundedAsciiWithoutRedisKeySyntax() {
        assertThat(GatewaySessionRevocation.validSid("76e253e4-a65b-427f-a40d-3db52b26b461")).isTrue();
        assertThat(GatewaySessionRevocation.validSid(null)).isFalse();
        assertThat(GatewaySessionRevocation.validSid(" ")).isFalse();
        assertThat(GatewaySessionRevocation.validSid("sid:other")).isFalse();
        assertThat(GatewaySessionRevocation.validSid("sid/other")).isFalse();
        assertThat(GatewaySessionRevocation.validSid("会话")).isFalse();
        assertThat(GatewaySessionRevocation.validSid("a".repeat(129))).isFalse();
    }
}
