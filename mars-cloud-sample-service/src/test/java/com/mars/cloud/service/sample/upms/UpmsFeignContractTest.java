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
        "springdoc.swagger-ui.enabled=false"
})
@ActiveProfiles({"local", "test"})
class UpmsFeignContractTest {

    private static final FakeUpms FAKE_UPMS = FakeUpms.start();

    @Autowired
    private UpmsDecisionAdapter adapter;

    @DynamicPropertySource
    static void discoveryProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.cloud.discovery.client.simple.instances.mars-cloud-upms-service[0].uri",
                FAKE_UPMS::uri);
    }

    @BeforeEach
    void resetUpstream() {
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
        assertThat(FAKE_UPMS.lastRequest().clientId()).isEqualTo("mars-cloud-sample-service");
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

    private record CapturedRequest(
            String method,
            String path,
            String subject,
            String clientId,
            String tenantId,
            String body) {
    }

    private record ResponseSpec(int status, byte[] body) {
    }

    private static final class FakeUpms implements AutoCloseable {

        private final HttpServer server;
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
            lastRequest.set(null);
        }

        CapturedRequest lastRequest() {
            return lastRequest.get();
        }

        private void handle(HttpExchange exchange) throws IOException {
            lastRequest.set(new CapturedRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst(InternalCallHeaders.SUBJECT),
                    exchange.getRequestHeaders().getFirst(InternalCallHeaders.CLIENT_ID),
                    exchange.getRequestHeaders().getFirst(InternalCallHeaders.TENANT_ID),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            ResponseSpec spec = response.get();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
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
