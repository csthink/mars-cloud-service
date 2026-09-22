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
 * 这样激活的 local / test 都不再算开发环境。非开发环境要求配好管理端点的凭据，
 * 所以这里一并给出，与真实部署一致。
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
// 本用例关掉 Web 安全装配来单测错误信封，因此也要排除管理端点的认证链：
// 它要求 ServerHttpSecurity 可用，缺了会让上下文起不来。
@org.springframework.test.context.TestPropertySource(properties = "spring.autoconfigure.exclude="
        + "com.mars.cloud.security.autoconfigure.ReactiveSecurityAutoConfiguration,"
        + "com.mars.cloud.security.autoconfigure.ServletSecurityAutoConfiguration,"
        + "com.mars.cloud.observability.autoconfigure.ReactiveManagementSecurityAutoConfiguration")
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
