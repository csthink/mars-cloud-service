package com.mars.cloud.service.gateway.sentinel;

import com.mars.cloud.sentinel.autoconfigure.MarsSentinelProperties;
import com.mars.cloud.sentinel.rule.RuleType;
import com.mars.cloud.service.gateway.GatewayApplication;
import com.mars.cloud.service.gateway.error.GatewayErrorCode;
import com.mars.cloud.service.gateway.web.GatewayIngressFilter;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 网关限流经真实的错误处理写出统一信封：429、码 {@code 63006}、按语言的文案；
 * 计数用入站过滤器核对后的客户端地址（本机可信代理数为 0，即 TCP 对端地址）。
 */
@SpringBootTest(classes = GatewayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.autoconfigure.exclude=com.mars.cloud.security.autoconfigure.ReactiveSecurityAutoConfiguration,com.mars.cloud.security.autoconfigure.ServletSecurityAutoConfiguration")
@ActiveProfiles({"local", "test"})
@AutoConfigureWebTestClient
@Import(GatewayRateLimitTest.UpstreamRoute.class)
class GatewayRateLimitTest {

    private static final HttpServer UPSTREAM = startUpstream();
    private static final String FLOW = RuleType.GATEWAY_FLOW.dataId("mars-cloud-gateway");

    @Autowired
    WebTestClient client;

    @Autowired
    MarsSentinelProperties sentinelProperties;

    @Autowired
    MeterRegistry registry;

    @BeforeAll
    static void limitTheRoute() {
        TestSentinelRules.publish(FLOW, """
                [{"resource":"rate-limit-upstream","count":1,"intervalSec":60,"paramItem":{"parseStrategy":0}}]
                """);
    }

    @AfterAll
    static void clear() {
        TestSentinelRules.reset();
        UPSTREAM.stop(0);
    }

    @Test
    void rejectedRequestsGetTheGatewayEnvelope() {
        client.get().uri("/rate-limit-upstream/ok").header("Host", "api.flippoabc.com")
                .exchange().expectStatus().isOk();

        client.get().uri("/rate-limit-upstream/ok").header("Host", "api.flippoabc.com")
                .header("Accept-Language", "zh-CN")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectBody()
                .jsonPath("$.success").isEqualTo(false)
                .jsonPath("$.code").isEqualTo(String.valueOf(GatewayErrorCode.RATE_LIMITED.getCode()))
                .jsonPath("$.message").isEqualTo("请求过于频繁，请稍后再试");

        assertThat(registry.get("mars.sentinel.requests.blocked").tag("resource", "rate-limit-upstream")
                .counter().count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void theLimiterCountsTheAddressThatTheIngressFilterVerified() {
        assertThat(sentinelProperties.getGateway().getClientIpAttribute())
                .isEqualTo(GatewayIngressFilter.CLIENT_IP_ATTRIBUTE);
    }

    private static HttpServer startUpstream() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/rate-limit-upstream/ok", exchange -> {
                byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class UpstreamRoute {

        @Bean
        RouteLocator rateLimitRoute(RouteLocatorBuilder builder) {
            return builder.routes().route("rate-limit-upstream", route -> route.path("/rate-limit-upstream/**")
                    .uri("http://127.0.0.1:" + UPSTREAM.getAddress().getPort())).build();
        }
    }
}
