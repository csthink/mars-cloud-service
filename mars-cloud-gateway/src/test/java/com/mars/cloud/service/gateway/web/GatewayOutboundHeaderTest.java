package com.mars.cloud.service.gateway.web;

import com.mars.cloud.service.gateway.GatewayApplication;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.junit.jupiter.api.Assertions.*;

/** 经过真实 HTTP 上游核对 Gateway 出站过滤器不会重新附加原始转发链。 */
@SpringBootTest(classes = GatewayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"local", "test"})
@AutoConfigureWebTestClient
@Import(GatewayOutboundHeaderTest.FakeUpstreamRoute.class)
class GatewayOutboundHeaderTest {
    private static final AtomicReference<Headers> RECEIVED = new AtomicReference<>();
    private static final HttpServer UPSTREAM = startUpstream();
    @Autowired WebTestClient client;

    private static HttpServer startUpstream() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/test-upstream/headers", exchange -> {
                RECEIVED.set(exchange.getRequestHeaders());
                byte[] body = "ok".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeUpstreamRoute {
        @Bean RouteLocator fakeRoute(RouteLocatorBuilder builder) {
            return builder.routes().route("test-upstream", route -> route.path("/test-upstream/**")
                    .uri("http://127.0.0.1:" + UPSTREAM.getAddress().getPort())).build();
        }
    }

    @AfterAll static void stopUpstream() { UPSTREAM.stop(0); }

    @Test
    void onlyCanonicalForwardingHeadersReachUpstream() {
        client.get().uri("/test-upstream/headers")
                .header("Host", "api.flippoabc.com")
                .header("Forwarded", "for=198.51.100.70")
                .header("X-Forwarded-For", "198.51.100.70, 198.51.100.71")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Custom", "spoof")
                .header("X-Mars-Subject", "spoof")
                .header("X-Mars-Client-Id", "spoof")
                .header("X-Mars-Tenant-Id", "spoof")
                .exchange().expectStatus().isOk();
        Headers headers = RECEIVED.get();
        assertNotNull(headers);
        assertEquals("127.0.0.1", headers.getFirst("X-Forwarded-For"), headers.toString());
        assertEquals(1, headers.get("X-Forwarded-For").size());
        assertEquals("api.flippoabc.com", headers.getFirst("X-Forwarded-Host"));
        assertEquals("http", headers.getFirst("X-Forwarded-Proto"));
        assertEquals("80", headers.getFirst("X-Forwarded-Port"));
        for (String name : new String[]{"Forwarded", "X-Forwarded-Custom", "X-Mars-Subject", "X-Mars-Client-Id", "X-Mars-Tenant-Id"})
            assertNull(headers.getFirst(name), name);
    }
}
