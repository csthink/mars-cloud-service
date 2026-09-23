package com.mars.cloud.service.gateway.web;

import com.mars.cloud.service.gateway.GatewayApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * 非开发环境下失败响应不得回带调试详情：{@code result} 必须缺席。
 *
 * <p>做法是把 {@code mars.env.dev-profiles} 改成一个测试进程不会激活的名字，
 * 这样激活的 local / test 都不再算开发环境。管理端点的凭据按真实部署一并给出；
 * 网关的 classpath 上没有 Spring Boot 的 Web 安全模块，建不起管理端点的认证链，
 * 可观测性组件因此把暴露面收窄为 health 与 info 并告警，上下文照常启动。
 */
@SpringBootTest(
        classes = GatewayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "mars.env.dev-profiles=production-like-only",
                "mars.observability.management.username=ops",
                "mars.observability.management.password=ops-secret"
        })
@ActiveProfiles({"local", "test"})
@AutoConfigureWebTestClient
@org.springframework.test.context.TestPropertySource(properties = "spring.autoconfigure.exclude=com.mars.cloud.security.autoconfigure.ReactiveSecurityAutoConfiguration,com.mars.cloud.security.autoconfigure.ServletSecurityAutoConfiguration")
class ProductionProfileEnvelopeTest {

    @Autowired
    private WebTestClient client;

    @Test
    void failureEnvelopeOmitsResultOutsideDevProfiles() {
        client.get().uri("/nothing-here").exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.success").isEqualTo(false)
                .jsonPath("$.code").isEqualTo("63001")
                .jsonPath("$.message").isNotEmpty()
                .jsonPath("$.result").doesNotExist();
    }
}
