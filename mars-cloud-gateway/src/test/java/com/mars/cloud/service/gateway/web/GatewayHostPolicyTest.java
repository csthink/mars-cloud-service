package com.mars.cloud.service.gateway.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

import static org.assertj.core.api.Assertions.assertThat;

/** 非法 Host 不应在 URI 解析后被归一化成合法域名。 */
class GatewayHostPolicyTest {

    @Test
    void emptyPortIsRejected() {
        var request = MockServerHttpRequest.get("http://127.0.0.1/product/v1/catalog")
                .header("Host", "api.flippoabc.com:").build();
        assertThat(GatewayHostPolicy.isApiHost(request, false)).isFalse();
    }
}
