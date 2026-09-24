package com.mars.cloud.service.gateway.web;

import com.mars.cloud.security.test.TestIdentityProvider;
import com.mars.cloud.service.gateway.GatewayApplication;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.mars.cloud.service.gateway.security.GatewaySessionRevocation;
import reactor.core.publisher.Mono;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;

/** Exercises the deployed Gateway security chain through its HTTP listener and routed upstream. */
@SpringBootTest(classes = GatewayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "logging.level.com.mars.cloud.service.gateway.web.EnvelopeErrorWebExceptionHandler=ERROR")
@ActiveProfiles({"local", "test", "security-integration"})
@AutoConfigureWebTestClient
class GatewayJwtRoutingTest {
    private static final TestIdentityProvider ISSUER = new TestIdentityProvider();
    private static final AtomicInteger UPSTREAM_CALLS = new AtomicInteger();
    private static final HttpServer UPSTREAM = startUpstream();
    private final Set<String> revokedKeys = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean redisAvailable = new AtomicBoolean(true);

    @Autowired WebTestClient web;
    @MockitoBean ReactiveStringRedisTemplate redis;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", ISSUER::issuer);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", ISSUER::jwksUri);
        registry.add("spring.cloud.discovery.client.simple.instances.mars-cloud-auth-service[0].uri",
                () -> "http://127.0.0.1:" + UPSTREAM.getAddress().getPort());
        registry.add("spring.cloud.discovery.client.simple.instances.mars-cloud-product-service[0].uri",
                () -> "http://127.0.0.1:" + UPSTREAM.getAddress().getPort());
    }

    private static HttpServer startUpstream() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                UPSTREAM_CALLS.incrementAndGet();
                byte[] response = "ok".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            server.start();
            return server;
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    @AfterAll
    static void stop() {
        UPSTREAM.stop(0);
        ISSUER.close();
    }

    @BeforeEach
    void reset() {
        UPSTREAM_CALLS.set(0);
        revokedKeys.clear();
        redisAvailable.set(true);
        when(redis.hasKey(anyString())).thenAnswer(call -> Mono.defer(() -> redisAvailable.get()
                ? Mono.just(revokedKeys.contains(call.getArgument(0)))
                : Mono.error(new IllegalStateException("private Redis connection details"))));
    }

    private static String signedToken(String sid) {
        var claims = ISSUER.claims("alice", "mars-cloud-gateway");
        claims.put("sid", sid);
        return ISSUER.sign(claims);
    }

    @Test
    void apiRouteRequiresAValidGatewayAudience() {
        web.get().uri("/auth/v1/me").header("Host", "api.flippoabc.com")
                .exchange().expectStatus().isUnauthorized().expectBody().jsonPath("$.code").isEqualTo("62001");
        web.get().uri("/auth/v1/me").header("Host", "api.flippoabc.com")
                .header("Authorization", "Bearer invalid")
                .exchange().expectStatus().isUnauthorized().expectBody().jsonPath("$.code").isEqualTo("62002");
        web.get().uri("/auth/v1/me").header("Host", "api.flippoabc.com")
                .headers(headers -> headers.setBearerAuth(ISSUER.token("alice", "mars-cloud-auth-service")))
                .exchange().expectStatus().isUnauthorized().expectBody().jsonPath("$.code").isEqualTo("62002");
        var expired = ISSUER.claims("alice", "mars-cloud-gateway");
        expired.put("exp", Date.from(Instant.now().minusSeconds(60)));
        web.get().uri("/auth/v1/me").header("Host", "api.flippoabc.com")
                .headers(headers -> headers.setBearerAuth(ISSUER.sign(expired)))
                .exchange().expectStatus().isUnauthorized().expectBody().jsonPath("$.code").isEqualTo("62002");
        assertThat(UPSTREAM_CALLS).hasValue(0);

        web.get().uri("/auth/v1/me").header("Host", "api.flippoabc.com")
                .headers(headers -> headers.setBearerAuth(signedToken(UUID.randomUUID().toString())))
                .exchange().expectStatus().isOk();
        assertThat(UPSTREAM_CALLS).hasValue(1);
    }

    @Test
    void missingOrInvalidSessionIdIsRejectedBeforeRedis() {
        web.get().uri("/auth/v1/me").header("Host", "api.flippoabc.com")
                .headers(headers -> headers.setBearerAuth(ISSUER.token("alice", "mars-cloud-gateway")))
                .exchange().expectStatus().isUnauthorized().expectBody().jsonPath("$.code").isEqualTo("62002");
        web.get().uri("/auth/v1/me").header("Host", "api.flippoabc.com")
                .headers(headers -> headers.setBearerAuth(signedToken("bad:sid")))
                .exchange().expectStatus().isUnauthorized().expectBody().jsonPath("$.code").isEqualTo("62002");
        var numericSid = ISSUER.claims("alice", "mars-cloud-gateway");
        numericSid.put("sid", 42);
        web.get().uri("/auth/v1/me").header("Host", "api.flippoabc.com")
                .headers(headers -> headers.setBearerAuth(ISSUER.sign(numericSid)))
                .exchange().expectStatus().isUnauthorized().expectBody().jsonPath("$.code").isEqualTo("62002");
        verifyNoInteractions(redis);
        assertThat(UPSTREAM_CALLS).hasValue(0);
    }

    @Test
    void revokedSessionIsRejectedAtGateway() {
        String sid = UUID.randomUUID().toString();
        revokedKeys.add(GatewaySessionRevocation.KEY_PREFIX + sid);
        web.get().uri("/auth/v1/me").header("Host", "api.flippoabc.com")
                .headers(headers -> headers.setBearerAuth(signedToken(sid)))
                .exchange().expectStatus().isUnauthorized().expectBody().jsonPath("$.code").isEqualTo("62002");
        verify(redis).hasKey(GatewaySessionRevocation.KEY_PREFIX + sid);
        assertThat(UPSTREAM_CALLS).hasValue(0);
    }

    @Test
    void unavailableRedisWithoutCachedSessionReturnsGateway503() {
        redisAvailable.set(false);
        String response = web.get().uri("/auth/v1/me").header("Host", "api.flippoabc.com")
                .headers(headers -> headers.setBearerAuth(signedToken(UUID.randomUUID().toString())))
                .exchange().expectStatus().isEqualTo(503).expectBody(String.class).returnResult().getResponseBody();
        assertThat(response).contains("\"code\":\"63005\"").doesNotContain("private Redis connection details");
        web.get().uri("/product/v1/catalog").header("Host", "api.flippoabc.com")
                .headers(headers -> headers.setBearerAuth(signedToken(UUID.randomUUID().toString())))
                .exchange().expectStatus().isEqualTo(503).expectBody().jsonPath("$.code").isEqualTo("63005");
        assertThat(UPSTREAM_CALLS).hasValue(0);
    }

    @Test
    void issuerAndUnmatchedRequestsKeepTheirRouteBoundary() {
        web.get().uri("/login").header("Host", "auth.flippoabc.com")
                .exchange().expectStatus().isOk();
        assertThat(UPSTREAM_CALLS).hasValue(1);

        web.get().uri("/auth/v1/me").header("Host", "auth.flippoabc.com")
                .exchange().expectStatus().isNotFound().expectBody().jsonPath("$.code").isEqualTo("63001");
        web.get().uri("/login").header("Host", "api.flippoabc.com")
                .exchange().expectStatus().isNotFound().expectBody().jsonPath("$.code").isEqualTo("63001");
        web.get().uri("/auth/v1/me").header("Host", "unknown.example")
                .exchange().expectStatus().isNotFound().expectBody().jsonPath("$.code").isEqualTo("63001");
        web.get().uri("/not-routed").header("Host", "api.flippoabc.com")
                .exchange().expectStatus().isNotFound().expectBody().jsonPath("$.code").isEqualTo("63001");
        web.get().uri("/auth%2Fv1/me").header("Host", "api.flippoabc.com")
                .exchange().expectStatus().is4xxClientError();
        web.get().uri("/auth/v1%2Fme").header("Host", "api.flippoabc.com")
                .exchange().expectStatus().is4xxClientError();
        assertThat(UPSTREAM_CALLS).hasValue(1);
    }

    @Test
    void publicReadsRemainAnonymousButPersonalAndAdminReadsRequireBearer() {
        web.get().uri("/product/v1/catalog").header("Host", "api.flippoabc.com")
                .exchange().expectStatus().isOk();
        web.get().uri("/product/v1/me").header("Host", "api.flippoabc.com")
                .exchange().expectStatus().isUnauthorized();
        web.get().uri("/product/v1/admin/items").header("Host", "api.flippoabc.com")
                .exchange().expectStatus().isUnauthorized();
        web.post().uri("/product/v1/catalog").header("Host", "api.flippoabc.com")
                .exchange().expectStatus().isUnauthorized();
        assertThat(UPSTREAM_CALLS).hasValue(1);
    }
}
