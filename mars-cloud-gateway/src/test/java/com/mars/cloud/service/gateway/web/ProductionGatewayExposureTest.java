package com.mars.cloud.service.gateway.web;

import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
import com.mars.cloud.sentinel.rule.RuleType;
import com.mars.cloud.service.gateway.GatewayApplication;
import com.mars.cloud.service.gateway.sentinel.TestSentinelRules;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/** 非本地 profile 只接受正式 API 与签发方 Host；路由暴露检查先于限流。 */
@SpringBootTest(classes = GatewayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"production", "test"})
@AutoConfigureWebTestClient
@org.springframework.test.context.TestPropertySource(properties = {
        "spring.autoconfigure.exclude=com.mars.cloud.security.autoconfigure.ReactiveSecurityAutoConfiguration,com.mars.cloud.security.autoconfigure.ServletSecurityAutoConfiguration",
        "logging.level.com.mars.cloud.service.gateway.web.EnvelopeErrorWebExceptionHandler=ERROR",
        "logging.level.org.springframework.cloud.loadbalancer.core.RoundRobinLoadBalancer=ERROR"
})
class ProductionGatewayExposureTest {

    private static final String FLOW = RuleType.GATEWAY_FLOW.dataId("mars-cloud-gateway");

    @Autowired
    private WebTestClient client;

    @Autowired
    private TestSentinelRules.Rules rules;

    @AfterEach
    void clearRules() {
        rules.publish(FLOW, "[]");
    }

    /** 不该暴露的路由每次都是 404：暴露检查在 Sentinel 网关过滤器之前执行，请求不被计数，不会变成 429。 */
    @Test
    void hiddenRoutesAre404BeforeTheRateLimiterCountsThem() {
        rules.publish(FLOW, "[{\"resource\":\"upms\",\"count\":1,\"intervalSec\":60,\"paramItem\":{\"parseStrategy\":0}}]");
        assertThat(GatewayRuleManager.getRules()).extracting(GatewayFlowRule::getResource).containsExactly("upms");

        for (int attempt = 0; attempt < 2; attempt++) {
            client.get().uri("/upms/v1/decision").header("Host", "api.flippoabc.com").exchange()
                    .expectStatus().isNotFound()
                    .expectBody().jsonPath("$.code").isEqualTo("63001");
        }
    }

    @Test
    void localHostAndTestServicesAreUnavailable() {
        client.get().uri("/product/v1/catalog").exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("63001");
        client.get().uri("/upms/v1/decision").header("Host", "api.flippoabc.com").exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("63001");
        client.get().uri("/product/v1/catalog").header("Host", "api.flippoabc.com").exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.code").isEqualTo("63002");
    }
}
