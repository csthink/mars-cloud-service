package com.mars.cloud.service.sample.upms;

import com.mars.cloud.common.context.CallerContextHolder;
import com.mars.cloud.common.context.InternalCallHeaders;
import com.mars.cloud.mvc.exception.HttpException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "springdoc.api-docs.enabled=false",
        "springdoc.swagger-ui.enabled=false",
        "spring.cloud.openfeign.client.config.default.loggerLevel=FULL",
        "spring.cloud.openfeign.client.config.marsSecurityPdp.followRedirects=true",
        "logging.level.com.mars.cloud.security.feign.PermissionDecisionApi=DEBUG"
})
@ActiveProfiles({"local", "test"})
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class UpmsFeignContractTest extends com.mars.cloud.service.sample.security.SecurityTestSupport {

    private static final FakeUpms FAKE_UPMS = FakeUpms.start();

    @Autowired
    private UpmsDecisionAdapter adapter;
    @Autowired private org.springframework.test.web.servlet.MockMvc mvc;

    @DynamicPropertySource
    static void discoveryProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.cloud.discovery.client.simple.instances.mars-cloud-upms-service[0].uri",
                FAKE_UPMS::uri);
    }

    @Autowired private org.springframework.security.oauth2.jwt.JwtDecoder jwtDecoder;
    @org.junit.jupiter.api.AfterEach void clearAuthentication() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }
    @BeforeEach
    void resetUpstream() {
        var jwt = jwtDecoder.decode(token("caller-allow"));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(jwt, java.util.List.of()));
        FAKE_UPMS.respond(200, """
                {"success":true,"result":{
                  "decision":"allow",
                  "reason_code":"granted",
                  "decision_id":"decision-1"
                }}
                """);
    }

    @AfterAll
    static void stopUpstream() {
        FAKE_UPMS.close();
    }

    @Test
    void callsDiscoveredUpmsAndPropagatesTrustedCallerHeaders() {
        UpmsDecisionResult result = adapter.decide(new SampleDecisionRequest(
                "write", "platform-a:namespace/workload"));

        assertThat(result.decision()).isEqualTo("allow");
        assertThat(result.reasonCode()).isEqualTo("granted");
        assertThat(FAKE_UPMS.lastRequest().method()).isEqualTo("POST");
        assertThat(FAKE_UPMS.lastRequest().path()).isEqualTo("/upms/v1/decision");
        assertThat(FAKE_UPMS.lastRequest().subject()).isEqualTo("caller-allow");
        assertThat(FAKE_UPMS.lastRequest().clientId()).isEqualTo("test-client");
        assertThat(FAKE_UPMS.lastRequest().tenantId()).isEqualTo("default");
        assertThat(FAKE_UPMS.lastRequest().body()).contains("\"caller_id\":\"caller-allow\"");
        assertThat(CallerContextHolder.current()).isEmpty();
    }

    @Test
    void downstream4xxMapsToSampleErrorWithoutLeakingMessage() {
        FAKE_UPMS.respond(400, """
                {"success":false,"code":"missing_field","message":"downstream secret"}
                """);

        assertThatThrownBy(() -> adapter.decide(new SampleDecisionRequest(
                "write", "platform-a:namespace/workload")))
                .isInstanceOfSatisfying(HttpException.class, failure -> {
                    assertThat(failure.getHttpStatusCode()).isEqualTo(502);
                    assertThat(failure.getErrCode()).isEqualTo(66103);
                    assertThat(failure.getMessage()).doesNotContain("downstream secret");
                });
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "{}",
            "{\"decision\":\"unexpected\",\"reason_code\":\"granted\",\"decision_id\":\"id-1\"}",
            "{\"decision\":\"allow\",\"reason_code\":\"\",\"decision_id\":\"id-1\"}",
            "{\"decision\":\"deny\",\"reason_code\":\"denied\"}",
            "{\"decision\":{\"private\":\"detail\"},\"reason_code\":\"granted\",\"decision_id\":\"id-1\"}"
    })
    void invalidDecisionResponseMapsToSampleError(String result) {
        FAKE_UPMS.respond(200, "{\"success\":true,\"result\":" + result + "}");
        assertThatThrownBy(() -> adapter.decide(new SampleDecisionRequest("view", "resource")))
                .isInstanceOfSatisfying(HttpException.class, failure -> {
                    assertThat(failure.getHttpStatusCode()).isEqualTo(502);
                    assertThat(failure.getErrCode()).isEqualTo(66103);
                    assertThat(failure).hasMessageNotContaining("detail");
                    assertThat(failure.getCause()).isNull();
                });
        assertThat(CallerContextHolder.current()).isEmpty();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {307, 308})
    void propertyOverridesCannotEnableCredentialRedirects(int status) {
        FAKE_UPMS.respond(status, "{\"success\":true,\"result\":{\"decision\":\"allow\",\"reason_code\":\"ok\",\"decision_id\":\"id\"}}");
        assertThatThrownBy(() -> adapter.decide(new SampleDecisionRequest("view", "resource")))
                .isInstanceOfSatisfying(HttpException.class, failure -> assertThat(failure.getHttpStatusCode()).isEqualTo(502));
        assertThat(FAKE_UPMS.calls).hasValue(1); assertThat(FAKE_UPMS.redirectHits).hasValue(0);
    }

    @Test void fullFeignLoggingNeverPrintsAccessToken(org.springframework.boot.test.system.CapturedOutput output) {
        adapter.decide(new SampleDecisionRequest("view", "resource"));
        String bearer = FAKE_UPMS.lastRequest().bearer();
        assertThat(bearer).startsWith("Bearer ");
        assertThat(output.getAll()).doesNotContain(bearer.substring(7));
    }

    @Test void sampleHttpIdentityRequiresValidTokenAndIgnoresSpoofedHeaders() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/security/me"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("62001"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/security/me")
                        .header("Authorization", "Bearer " + token("alice")).header("X-Mars-Subject", "forged"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.result.subject").value("alice"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"allow,200,200,", "deny,200,403,62003", "allow,503,503,62004", "bad,200,502,62005"})
    void sampleMethodSecurityUsesRealPdp(String decision, int remoteStatus, int status, String code) throws Exception {
        FAKE_UPMS.respond(remoteStatus, "{\"success\":true,\"result\":{\"decision\":\"" + decision + "\",\"reason_code\":\"test\",\"decision_id\":\"id\"}}");
        var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/security/decision")
                .header("Authorization", "Bearer " + token("caller-allow"))).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        if (code != null) assertThat(response.getContentAsString()).contains("\"code\":\"" + code + "\"");
        assertThat(FAKE_UPMS.calls).hasValue(1);
    }

    @Test void developmentResponseAndLogsExcludeCredentials(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        String accessToken = token("alice");
        var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/orders/missing")
                .header("Authorization", "Bearer " + accessToken).header("Proxy-Authorization", "sentinel-proxy-secret")
                .header("Cookie", "session=sentinel-cookie-secret").header("Set-Cookie", "sentinel-set-cookie-secret")
                .header("X-Api-Key", "sentinel-api-secret").header("X-Debug-Marker", "visible-marker"))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).contains("visible-marker");
        for (String secret : java.util.List.of(accessToken, "sentinel-proxy-secret", "sentinel-cookie-secret", "sentinel-set-cookie-secret", "sentinel-api-secret")) {
            assertThat(response).doesNotContain(secret); assertThat(output.getAll()).doesNotContain(secret);
        }
    }

    private record CapturedRequest(
            String method,
            String path,
            String subject,
            String clientId,
            String tenantId,
            String body,
            String bearer) {
    }

    private record ResponseSpec(int status, byte[] body) {
    }

    private static final class FakeUpms implements AutoCloseable {

        private final HttpServer server;
        private final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicInteger redirectHits = new java.util.concurrent.atomic.AtomicInteger();
        private final AtomicReference<ResponseSpec> response = new AtomicReference<>();
        private final AtomicReference<CapturedRequest> lastRequest = new AtomicReference<>();

        private FakeUpms(HttpServer server) {
            this.server = server;
        }

        static FakeUpms start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                FakeUpms fake = new FakeUpms(server);
                server.createContext("/upms/v1/decision", fake::handle);
                server.createContext("/capture", exchange -> {
                    fake.redirectHits.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close();
                });
                server.start();
                return fake;
            } catch (IOException ex) {
                throw new IllegalStateException("无法启动假 UPMS", ex);
            }
        }

        String uri() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void respond(int status, String body) {
            response.set(new ResponseSpec(status, body.getBytes(StandardCharsets.UTF_8)));
            lastRequest.set(null); calls.set(0); redirectHits.set(0);
        }

        CapturedRequest lastRequest() {
            return lastRequest.get();
        }

        private void handle(HttpExchange exchange) throws IOException {
            calls.incrementAndGet();
            lastRequest.set(new CapturedRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst(InternalCallHeaders.SUBJECT),
                    exchange.getRequestHeaders().getFirst(InternalCallHeaders.CLIENT_ID),
                    exchange.getRequestHeaders().getFirst(InternalCallHeaders.TENANT_ID),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                    exchange.getRequestHeaders().getFirst("Authorization")));
            ResponseSpec spec = response.get();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Location", uri() + "/capture");
            exchange.sendResponseHeaders(spec.status(), spec.body().length);
            exchange.getResponseBody().write(spec.body());
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
