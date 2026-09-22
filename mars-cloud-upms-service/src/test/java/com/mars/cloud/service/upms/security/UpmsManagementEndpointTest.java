package com.mars.cloud.service.upms.security;

import com.mars.cloud.service.upms.UpmsApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 管理端点在真实端口上的行为：与业务端口分开，health 匿名可读，指标要 Basic 认证。
 *
 * <p>模拟请求到不了管理子上下文，所以这条必须用真端口。业务端口上不应出现管理端点，
 * 否则内网之外的调用方也能读到运行细节。
 *
 * <p>继承测试签发器支持类：本服务的资源服务器装配需要可用的签发者与公钥端点，
 * 缺了它上下文根本起不来，与管理端点无关。
 */
@SpringBootTest(classes = UpmsApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.server.port=0",
                "mars.observability.management.username=ops",
                "mars.observability.management.password=ops-secret"
        })
@ActiveProfiles({"local", "test"})
class UpmsManagementEndpointTest extends SecurityTestSupport {

    @LocalServerPort int businessPort;
    @LocalManagementPort int managementPort;

    @Test void managementPortIsSeparateFromTheBusinessPort() {
        assertThat(managementPort).isPositive().isNotEqualTo(businessPort);
    }

    @Test void healthIsAnonymousAndMetricsNeedCredentials() {
        assertThat(get(managementPort, "/actuator/health", null, null).getStatusCode().value())
                .isEqualTo(HttpStatus.OK.value());
        assertThat(get(managementPort, "/actuator/prometheus", null, null).getStatusCode().value())
                .isEqualTo(HttpStatus.UNAUTHORIZED.value());
        ResponseEntity<String> allowed = get(managementPort, "/actuator/prometheus", "ops", "ops-secret");
        assertThat(allowed.getStatusCode().value()).isEqualTo(HttpStatus.OK.value());
        assertThat(allowed.getBody()).contains("application=\"mars-cloud-upms-service\"");
    }

    /**
     * 业务端口上没有管理端点。具体状态码取决于本服务业务安全链对未知路径的处理，
     * 这里只断言拿不到健康响应，不把某一个码钉死。
     */
    @Test void actuatorIsNotReachableOnTheBusinessPort() {
        ResponseEntity<String> response = get(businessPort, "/upms/actuator/health", null, null);
        assertThat(response.getStatusCode().is2xxSuccessful()).isFalse();
        assertThat(response.getBody()).doesNotContain("\"status\":\"UP\"");
    }

    private static ResponseEntity<String> get(int port, String path, String user, String password) {
        RestClient.RequestHeadersSpec<?> request = RestClient.builder().build()
                .get().uri("http://127.0.0.1:" + port + path);
        if (user != null) {
            request = request.headers(headers -> headers.setBasicAuth(user, password));
        }
        return request.retrieve()
                .onStatus(status -> true, (ignoredRequest, ignoredResponse) -> { })
                .toEntity(String.class);
    }
}
