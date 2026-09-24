package com.mars.cloud.service.gateway.web;

import com.mars.cloud.service.gateway.GatewayApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** Checks the packaged gateway's separate management chain with real HTTP requests. */
@SpringBootTest(classes = GatewayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "mars.observability.management.username=ops",
                "mars.observability.management.password=test-password",
                "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://127.0.0.1:1",
                "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:1/keys"})
@ActiveProfiles({"local", "test", "security-integration"})
class GatewayManagementSecurityTest {
    @LocalServerPort int businessPort;
    @LocalManagementPort int managementPort;

    @Test
    void prometheusRequiresBasicAuthenticationOnManagementPort() throws Exception {
        assertThat(managementPort).isPositive().isNotEqualTo(businessPort);
        assertThat(status(managementPort, "/actuator/health", null)).isEqualTo(200);
        assertThat(status(managementPort, "/actuator/prometheus", null)).isEqualTo(401);
        assertThat(status(managementPort, "/actuator/prometheus", "ops:wrong")).isEqualTo(401);
        assertThat(status(managementPort, "/actuator/prometheus", "ops:test-password")).isEqualTo(200);
        assertThat(status(businessPort, "/actuator/prometheus", null)).isEqualTo(404);
    }

    private static int status(int port, String path, String credentials) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET();
        if (credentials != null) {
            request.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                    credentials.getBytes(StandardCharsets.UTF_8)));
        }
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        }
    }
}
