package com.mars.cloud.service.gateway.sentinel;

import com.alibaba.csp.sentinel.adapter.gateway.common.api.GatewayApiDefinitionManager;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
import com.mars.cloud.sentinel.autoconfigure.MarsSentinelProperties;
import com.mars.cloud.sentinel.rule.RuleType;
import com.mars.cloud.service.gateway.GatewayApplication;
import com.mars.cloud.service.gateway.error.GatewayErrorCode;
import com.mars.cloud.service.gateway.web.GatewayIngressFilter;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 网关限流经真实的错误处理写出统一信封：429、码 {@code 63006}、按语言的文案；
 * 客户端地址的交换属性名与入站过滤器写入的一致；本机基线规则能通过路由 ID 与分组的校验；
 * 基线里的认证入口分组按来源地址计数。
 *
 * <p>上下文以可信代理数量 1 启动，每个请求用 {@code X-Forwarded-For} 给出客户端地址，同一个 TCP 对端因此能扮演不同的客户端。
 * 路由用例的安全链（{@code security-integration} 之外的测试 profile）放行全部请求，令牌验证由安全用例覆盖。
 * 放行到 auth-issuer 路由的请求在测试进程里没有实例、以 503 结束，负载均衡与网关为它写的 WARN 是预期内的，
 * 与其他契约用例一样只在本上下文把这两个记录器降到 ERROR，不改生产配置。
 */
@SpringBootTest(classes = GatewayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"mars.gateway.proxy.trusted-hop-count=1", "mars.gateway.proxy.direct-peer-cidrs=127.0.0.1/32",
                "logging.level.com.mars.cloud.service.gateway.web.EnvelopeErrorWebExceptionHandler=ERROR",
                "logging.level.org.springframework.cloud.loadbalancer.core.RoundRobinLoadBalancer=ERROR"})
@ActiveProfiles({"local", "test"})
@AutoConfigureWebTestClient
@Import(GatewayRateLimitTest.UpstreamRoute.class)
class GatewayRateLimitTest {

    private static final HttpServer UPSTREAM = startUpstream();
    private static final String FLOW = RuleType.GATEWAY_FLOW.dataId("mars-cloud-gateway");
    private static final String GROUPS = RuleType.GATEWAY_API_GROUP.dataId("mars-cloud-gateway");
    private static final Path BASELINE = Path.of("..", "dev", "config", "sentinel");
    private static final String API_HOST = "api.flippoabc.com";
    private static final String AUTH_HOST = "auth.flippoabc.com";

    @Autowired
    WebTestClient client;

    @Autowired
    MarsSentinelProperties sentinelProperties;

    @Autowired
    MeterRegistry registry;

    @Autowired
    TestSentinelRules.Rules rules;

    @AfterAll
    static void stopUpstream() {
        UPSTREAM.stop(0);
    }

    /** 规则在运行中下发到本用例的上下文，结束时清空，不影响复用这个上下文的其他用例；先清限流规则，再清它引用的分组。 */
    @AfterEach
    void clearRules() {
        rules.publish(FLOW, "[]");
        rules.publish(GROUPS, "[]");
    }

    @Test
    void rejectedRequestsGetTheGatewayEnvelope() {
        rules.publish(FLOW, """
                [{"resource":"rate-limit-upstream","count":1,"intervalSec":60,"paramItem":{"parseStrategy":0}}]
                """);
        send(HttpMethod.GET, "/rate-limit-upstream/ok", API_HOST, "198.51.100.1").exchange().expectStatus().isOk();

        send(HttpMethod.GET, "/rate-limit-upstream/ok", API_HOST, "198.51.100.1")
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
    void theClientAddressAttributeIsTheOneTheIngressFilterWrites() {
        assertThat(sentinelProperties.getGateway().getClientIpAttribute())
                .isEqualTo(GatewayIngressFilter.CLIENT_IP_ATTRIBUTE);
    }

    /** {@code dev/config/sentinel/} 的基线：分组先装入，七条路由规则与两条分组规则都引用已声明的名字，整批被接受。 */
    @Test
    void theLocalBaselineRulesAreAccepted() throws IOException {
        rules.publish(GROUPS, Files.readString(BASELINE.resolve(GROUPS)));
        rules.publish(FLOW, Files.readString(BASELINE.resolve(FLOW)));

        assertThat(GatewayApiDefinitionManager.getApiDefinitions()).hasSize(2);
        assertThat(GatewayRuleManager.getRules()).hasSize(9);
        assertThat(registry.get("mars.sentinel.rule.source.valid").tag("rule_type", "gw-flow").gauge().value()).isEqualTo(1);
        assertThat(registry.get("mars.sentinel.rules.active").tag("rule_type", "gw-flow").gauge().value()).isEqualTo(9);
    }

    /**
     * 基线分组 {@code auth-entry}：发码、验证码校验、令牌与授权端点共用一个按来源地址的计数，超过阈值的地址得到 429 与
     * {@code 63006}；另一个地址与分组外的登录页仍到达 auth-issuer 路由。放行的请求在测试进程里没有 auth-service 实例，
     * 网关以 503 与 {@code 63002} 结束。
     */
    @Test
    void theAuthEntryGroupCountsPerClientAddress() throws IOException {
        rules.publish(GROUPS, Files.readString(BASELINE.resolve(GROUPS)));
        rules.publish(FLOW, """
                [{"resource":"auth-entry","resourceMode":1,"count":2,"intervalSec":60,"paramItem":{"parseStrategy":0}}]
                """);
        String limited = "198.51.100.7";

        expectRoutedToAuthService(send(HttpMethod.POST, "/oauth2/token", AUTH_HOST, limited));
        expectRoutedToAuthService(send(HttpMethod.POST, "/login/sms/send", AUTH_HOST, limited));
        send(HttpMethod.POST, "/login/sms/authenticate", AUTH_HOST, limited)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectBody()
                .jsonPath("$.success").isEqualTo(false)
                .jsonPath("$.code").isEqualTo(String.valueOf(GatewayErrorCode.RATE_LIMITED.getCode()));

        expectRoutedToAuthService(send(HttpMethod.GET, "/oauth2/authorize", AUTH_HOST, "198.51.100.8"));
        expectRoutedToAuthService(send(HttpMethod.GET, "/login", AUTH_HOST, limited));

        assertThat(registry.get("mars.sentinel.requests.blocked").tag("resource", "auth-entry")
                .counter().count()).isGreaterThanOrEqualTo(1);
    }

    /** 以可信代理的身份发请求：{@code X-Forwarded-For} 给出客户端地址，入站过滤器把它写进交换属性。 */
    private WebTestClient.RequestBodySpec send(HttpMethod method, String path, String host, String clientAddress) {
        return client.method(method).uri(path)
                .header("Host", host)
                .header("X-Forwarded-For", clientAddress)
                .header("X-Forwarded-Proto", "http");
    }

    private static void expectRoutedToAuthService(WebTestClient.RequestBodySpec request) {
        request.exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectBody()
                .jsonPath("$.code").isEqualTo(String.valueOf(GatewayErrorCode.UPSTREAM_NO_INSTANCE.getCode()));
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
