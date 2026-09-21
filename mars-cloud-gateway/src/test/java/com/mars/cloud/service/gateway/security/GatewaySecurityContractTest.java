package com.mars.cloud.service.gateway.security;

import com.mars.cloud.security.*;
import com.mars.cloud.security.reactive.*;
import com.mars.cloud.security.test.TestIdentityProvider;
import com.mars.cloud.service.gateway.GatewayApplication;
import com.mars.cloud.service.gateway.error.GatewayErrorCode;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.system.*;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.test.context.*;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes = GatewayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"mars.security.audience=mars-cloud-gateway", "spring.cloud.loadbalancer.retry.enabled=true"})
@Import(GatewaySecurityContractTest.TestPolicy.class)
@ActiveProfiles({"local", "test", "security-integration"})
@AutoConfigureWebTestClient
@ExtendWith(OutputCaptureExtension.class)
class GatewaySecurityContractTest {
    private static final TestIdentityProvider ISSUER = new TestIdentityProvider();
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final AtomicInteger EXECUTIONS = new AtomicInteger();
    private static final AtomicReference<String> FORWARDED = new AtomicReference<>();
    private static volatile String decision = "allow";
    private static volatile int downstreamStatus = 200;
    private static final HttpServer PDP = startPdp();
    @Autowired WebTestClient web;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", ISSUER::issuer);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", ISSUER::jwksUri);
        registry.add("spring.cloud.discovery.client.simple.instances.mars-cloud-upms-service[0].uri",
                () -> "http://127.0.0.1:" + PDP.getAddress().getPort());
    }
    private static HttpServer startPdp() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(PdpProtocol.PATH, exchange -> {
                CALLS.incrementAndGet(); FORWARDED.set(exchange.getRequestHeaders().getFirst("Authorization"));
                String body = "{\"success\":true,\"result\":{\"decision\":\"" + decision
                        + "\",\"reason_code\":\"test\",\"decision_id\":\"id\"}}";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(downstreamStatus, bytes.length);
                exchange.getResponseBody().write(bytes); exchange.close();
            }); server.start(); return server;
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    @AfterAll static void stop() { PDP.stop(0); ISSUER.close(); }
    @BeforeEach void reset() { CALLS.set(0); EXECUTIONS.set(0); decision = "allow"; downstreamStatus = 200; }
    @TestConfiguration(proxyBeanMethods = false)
    @Import(Endpoints.class)
    static class TestPolicy {
        @Bean SecurityWebFilterChain testSecurity(ServerHttpSecurity http, MarsReactiveSecurityConfigurer configurer) {
            http.csrf(csrf -> csrf.disable()).authorizeExchange(requests -> requests.anyExchange().authenticated());
            return configurer.configure(http).build();
        }
    }
    @RestController @Profile("security-integration")
    static class Endpoints {
        @GetMapping("/security-test/me") Mono<com.mars.cloud.common.context.CallerContext> me() { return ReactiveCallerContext.current(); }
        @GetMapping("/security-test/decision") @PreAuthorize("@marsAuthorization.allowed('view','demo:view:domain:kubernetes-ops')")
        Mono<Map<String, Boolean>> guarded() { EXECUTIONS.incrementAndGet(); return Mono.just(Map.of("allowed", true)); }
    }
    @Test void rejectsInvalidAndMissingBearer() {
        web.get().uri("/security-test/me").exchange().expectStatus().isUnauthorized().expectBody().jsonPath("$.code").isEqualTo("62001");
        web.get().uri("/security-test/me").header("Authorization", "Bearer sentinel-invalid")
                .exchange().expectStatus().isUnauthorized().expectBody().jsonPath("$.code").isEqualTo("62002");
        assertThat(CALLS).hasValue(0);
    }
    @Test void verifiedIdentityIgnoresSpoofedInternalHeader() {
        web.get().uri("/security-test/me").headers(headers -> headers.setBearerAuth(ISSUER.token("alice", "mars-cloud-gateway")))
                .header("X-Mars-Subject", "forged").exchange().expectStatus().isOk().expectBody().jsonPath("$.subject").isEqualTo("alice");
        assertThat(CALLS).hasValue(0);
    }
    @ParameterizedTest @CsvSource({"allow,200,200,", "deny,200,403,62003", "bad,200,502,62005", "allow,503,503,62004", "allow,401,502,62005"})
    void realGatewayMethodUsesDiscoveredPdpWithoutRetry(String outcome, int remoteStatus, int status, String code) {
        decision = outcome; downstreamStatus = remoteStatus;
        String token = ISSUER.token("alice", "mars-cloud-gateway", "mars-cloud-upms-service");
        var result = web.get().uri("/security-test/decision").headers(headers -> headers.setBearerAuth(token)).exchange().expectStatus().isEqualTo(status);
        if (code != null) result.expectBody().jsonPath("$.code").isEqualTo(code);
        assertThat(CALLS).hasValue(1);
        assertThat(EXECUTIONS).hasValue(status == 200 ? 1 : 0);
        assertThat(FORWARDED.get()).isEqualTo("Bearer " + token);
    }
    @Test void developmentErrorsAndLogsExcludeCredentialSentinels(CapturedOutput output) {
        String token = ISSUER.token("alice", "mars-cloud-gateway");
        var response = web.get().uri("/security-test/missing").headers(headers -> headers.setBearerAuth(token))
                .header("Proxy-Authorization", "sentinel-proxy-secret").header("Cookie", "session=sentinel-cookie-secret")
                .header("Set-Cookie", "sentinel-set-cookie-secret").header("X-Api-Key", "sentinel-api-secret")
                .header("X-Debug-Marker", "visible-marker")
                .exchange().expectStatus().isNotFound().expectBody(String.class).returnResult().getResponseBody();
        assertThat(response).contains("visible-marker");
        for (String secret : List.of(token, "sentinel-proxy-secret", "sentinel-cookie-secret", "sentinel-set-cookie-secret", "sentinel-api-secret")) {
            assertThat(response).doesNotContain(secret); assertThat(output.getAll()).doesNotContain(secret);
        }
    }
    @Test void securityAndGatewayCodesDoNotOverlapAndBlockingAdaptersAreAbsent() {
        var codes = new HashSet<Integer>();
        for (var code : SecurityErrorCode.values()) { assertThat(code.getCode()).isBetween(62000,62999); assertThat(codes.add(code.getCode())).isTrue(); }
        for (var code : GatewayErrorCode.values()) { assertThat(code.getCode()).isBetween(63000,63999); assertThat(codes.add(code.getCode())).isTrue(); }
        assertThat(org.springframework.util.ClassUtils.isPresent("feign.Feign", getClass().getClassLoader())).isFalse();
        assertThat(org.springframework.util.ClassUtils.isPresent("org.springframework.web.servlet.DispatcherServlet", getClass().getClassLoader())).isFalse();
    }
}
