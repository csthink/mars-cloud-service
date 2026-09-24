package com.mars.cloud.service.gateway.web;

import com.mars.cloud.service.gateway.GatewayApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

/** 用实际 HTTP 入口核对 Host 与 API 跨域行为。 */
@SpringBootTest(classes = GatewayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"local", "test"})
@AutoConfigureWebTestClient
@org.springframework.test.context.TestPropertySource(properties = {
        "spring.autoconfigure.exclude=com.mars.cloud.security.autoconfigure.ReactiveSecurityAutoConfiguration,com.mars.cloud.security.autoconfigure.ServletSecurityAutoConfiguration",
        "logging.level.com.mars.cloud.service.gateway.web.EnvelopeErrorWebExceptionHandler=ERROR",
        "logging.level.org.springframework.cloud.loadbalancer.core.RoundRobinLoadBalancer=ERROR"
})
class GatewayHostAndCorsContractTest {

    @Autowired
    private WebTestClient client;

    @LocalServerPort
    private int port;

    @Test
    void apiHostRoutesToUnavailableServiceButAuthHostCannotReachBusinessApi() {
        client.get().uri("/product/v1/catalog").header("Host", "api.flippoabc.com").exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.code").isEqualTo("63002");
        client.get().uri("/auth/v1/me").header("Host", "auth.flippoabc.com").exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("63001");
        client.get().uri("/product/v1/catalog").header("Host", "unknown.flippoabc.com").exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("63001");
    }

    @Test
    void issuerPathNeedsAuthHost() {
        client.get().uri("/login").header("Host", "auth.flippoabc.com").exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.code").isEqualTo("63002");
        client.get().uri("/login").header("Host", "api.flippoabc.com").exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("63001");
    }

    @Test
    void issuerAcceptsCaseInsensitiveHostWithHttpsPortAndLocalListeningPort() {
        client.get().uri("/login").header("Host", "AUTH.FLIPPOABC.COM:443").exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.code").isEqualTo("63002");
        client.get().uri("/login").header("Host", "localhost:" + port).exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.code").isEqualTo("63002");
        client.get().uri("/login").header("Host", "auth.flippoabc.com:8443").exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("63001");
    }

    @Test
    void listedOriginGetsOnlyTheRequestedApiPermission() {
        client.options().uri("/product/v1/catalog")
                .header("Host", "api.flippoabc.com")
                .header("Origin", "https://flippoabc.com")
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "authorization")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "https://flippoabc.com")
                .expectHeader().doesNotExist("Access-Control-Allow-Credentials");
        client.get().uri("/product/v1/catalog")
                .header("Host", "api.flippoabc.com")
                .header("Origin", "https://word.flippoabc.com")
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "https://word.flippoabc.com");
    }

    @Test
    void unlistedOriginAndHeaderAreRejected() {
        client.options().uri("/product/v1/catalog")
                .header("Host", "api.flippoabc.com")
                .header("Origin", "https://book.flippoabc.com")
                .header("Access-Control-Request-Method", "GET")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().doesNotExist("Access-Control-Allow-Origin");
        client.get().uri("/product/v1/catalog")
                .header("Host", "api.flippoabc.com")
                .header("Origin", "https://other.example")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().doesNotExist("Access-Control-Allow-Origin");
        client.options().uri("/product/v1/catalog")
                .header("Host", "api.flippoabc.com")
                .header("Origin", "https://console.flippoabc.com")
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "x-unlisted")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void issuerAndUnmatchedPathsDoNotGrantCors() {
        client.options().uri("/userinfo")
                .header("Host", "auth.flippoabc.com")
                .header("Origin", "https://flippoabc.com")
                .header("Access-Control-Request-Method", "GET")
                .exchange()
                .expectHeader().doesNotExist("Access-Control-Allow-Origin");
        client.options().uri("/no-such-path")
                .header("Host", "api.flippoabc.com")
                .header("Origin", "https://flippoabc.com")
                .header("Access-Control-Request-Method", "GET")
                .exchange()
                .expectHeader().doesNotExist("Access-Control-Allow-Origin");
    }
}
