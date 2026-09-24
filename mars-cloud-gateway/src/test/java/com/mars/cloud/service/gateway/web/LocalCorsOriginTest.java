package com.mars.cloud.service.gateway.web;

import com.mars.cloud.service.gateway.GatewayApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

/** 本地浏览器来源只能由完整回环 origin 显式加入。 */
@SpringBootTest(
        classes = GatewayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "mars.gateway.cors.local-origins=http://127.0.0.1:5173")
@ActiveProfiles({"local", "test"})
@AutoConfigureWebTestClient
@org.springframework.test.context.TestPropertySource(properties = "spring.autoconfigure.exclude=com.mars.cloud.security.autoconfigure.ReactiveSecurityAutoConfiguration,com.mars.cloud.security.autoconfigure.ServletSecurityAutoConfiguration")
class LocalCorsOriginTest {

    @Autowired
    private WebTestClient client;

    @Test
    void configuredLoopbackOriginIsAllowedOnlyByExactMatch() {
        client.options().uri("/product/v1/catalog")
                .header("Host", "api.flippoabc.com")
                .header("Origin", "http://127.0.0.1:5173")
                .header("Access-Control-Request-Method", "GET")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "http://127.0.0.1:5173");
        client.options().uri("/product/v1/catalog")
                .header("Host", "api.flippoabc.com")
                .header("Origin", "http://127.0.0.1:5174")
                .header("Access-Control-Request-Method", "GET")
                .exchange()
                .expectStatus().isForbidden();
    }
}
