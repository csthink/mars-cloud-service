package com.mars.cloud.service.gateway.web;

import com.mars.cloud.service.gateway.GatewayApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;

/**
 * 网关侧统一响应的契约：网关自产错误用与业务服务同一种信封；业务服务的响应原样透传。
 *
 * <p>上游是 {@link FakeUpstream}，通过 Spring Cloud 自带的 simple discovery client 注册为
 * {@code mars-cloud-upms-service} 的唯一实例，走的是 {@code application.yml} 里真实的 lb:// 路由；
 * 三条只在测试里存在的路由（无实例、拒绝连接、响应超时）由 {@link ExtraRoutes} 追加。
 */
@SpringBootTest(classes = GatewayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ErrorEnvelopeContractTest.ExtraRoutes.class)
@ActiveProfiles({"local", "test"})
@AutoConfigureWebTestClient
@org.springframework.test.context.TestPropertySource(properties = "spring.autoconfigure.exclude=com.mars.cloud.security.autoconfigure.ReactiveSecurityAutoConfiguration,com.mars.cloud.security.autoconfigure.ServletSecurityAutoConfiguration")
class ErrorEnvelopeContractTest {

    @DynamicPropertySource
    static void registerFakeUpstream(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.discovery.client.simple.instances.mars-cloud-upms-service[0].uri",
                () -> "http://127.0.0.1:" + FakeUpstream.port());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ExtraRoutes {

        @Bean
        RouteLocator testOnlyRoutes(RouteLocatorBuilder builder) {
            return builder.routes()
                    .route("no-instance", r -> r.path("/no-instance/**").uri("lb://no-such-service"))
                    // 绑定后立刻释放的端口上没有任何监听，连接会被立刻拒绝。
                    .route("refused", r -> r.path("/refused/**").uri("http://127.0.0.1:" + closedPort()))
                    .route("slow", r -> r.path("/upms/slow")
                            .metadata("response-timeout", 200)
                            .uri("lb://mars-cloud-upms-service"))
                    .build();
        }

        private static int closedPort() {
            try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                return socket.getLocalPort();
            } catch (IOException e) {
                throw new IllegalStateException("无法取得空闲端口", e);
            }
        }
    }

    @Autowired
    private WebTestClient client;

    @Test
    void routedSuccessIsPassedThroughUntouched() {
        client.get().uri("/upms/actuator/health").exchange()
                .expectStatus().isOk()
                .expectBody().json(FakeUpstream.HEALTH_BODY);
    }

    @Test
    void upstreamErrorEnvelopeIsPassedThroughUntouched() {
        // 业务服务自己的失败信封（含它的错误码）不被网关改写，也不被二次包装。
        client.post().uri("/upms/v1/decision").contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange()
                .expectStatus().isBadRequest()
                .expectBody().json(FakeUpstream.UPSTREAM_ERROR_BODY);
    }

    @Test
    void unmatchedPathIsRouteNotFoundEnvelope() {
        client.get().uri("/nothing-here").exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.success").isEqualTo(false)
                .jsonPath("$.code").isEqualTo("63001")
                .jsonPath("$.message").isNotEmpty();
    }

    @Test
    void serviceWithoutInstancesIsUpstreamNoInstanceEnvelope() {
        client.get().uri("/no-instance/anything").exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.success").isEqualTo(false)
                .jsonPath("$.code").isEqualTo("63002")
                .jsonPath("$.message").isNotEmpty();
    }

    @Test
    void refusedConnectionIsUpstreamConnectFailedEnvelope() {
        client.get().uri("/refused/anything").exchange()
                .expectStatus().isEqualTo(502)
                .expectBody()
                .jsonPath("$.success").isEqualTo(false)
                .jsonPath("$.code").isEqualTo("63003");
    }

    @Test
    void slowUpstreamIsUpstreamTimeoutEnvelope() {
        client.get().uri("/upms/slow").exchange()
                .expectStatus().isEqualTo(504)
                .expectBody()
                .jsonPath("$.success").isEqualTo(false)
                .jsonPath("$.code").isEqualTo("63004");
    }

    @Test
    void devProfileCarriesDebugDetailsWithSameKeysAsServletSide() {
        // 测试进程激活 local,test，命中 mars.env.dev-profiles。
        client.get().uri("/nothing-here").exchange()
                .expectBody()
                .jsonPath("$.result.detail").exists()
                .jsonPath("$.result.method").isEqualTo("GET")
                .jsonPath("$.result.uri").isEqualTo("/nothing-here")
                .jsonPath("$.result.headers").exists();
    }

    @Test
    void messageFollowsAcceptLanguage() {
        client.get().uri("/nothing-here").header("Accept-Language", "zh-CN").exchange()
                .expectBody().jsonPath("$.message").isEqualTo("网关没有匹配该路径的路由");
        client.get().uri("/nothing-here").header("Accept-Language", "en-US").exchange()
                .expectBody().jsonPath("$.message").isEqualTo("No route matched the request path");
    }

    @Test
    void gatewayOwnActuatorHealthIsUp() {
        client.get().uri("/actuator/health").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP");
    }
}
