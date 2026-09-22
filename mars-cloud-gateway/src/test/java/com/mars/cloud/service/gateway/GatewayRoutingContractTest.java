package com.mars.cloud.service.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 路由表的契约：只有显式声明的路由，目标走注册中心的负载均衡，且不自动暴露注册中心里的服务。
 */
@SpringBootTest(classes = GatewayApplication.class)
@ActiveProfiles({"local", "test"})
@org.springframework.test.context.TestPropertySource(properties = "spring.autoconfigure.exclude=com.mars.cloud.security.autoconfigure.ReactiveSecurityAutoConfiguration,com.mars.cloud.security.autoconfigure.ServletSecurityAutoConfiguration")
class GatewayRoutingContractTest {

    @Autowired
    private RouteLocator routeLocator;

    @Autowired
    private Environment environment;

    @Test
    void declaredRoutesTargetServiceNamesThroughLoadBalancer() {
        List<Route> routes = routeLocator.getRoutes().collectList().block();

        assertThat(routes).isNotNull();
        // 路由总数一起断言：新增路由必须先让这条用例失败，再由改动者确认它确实该被暴露。
        assertThat(routes).extracting(Route::getId).containsExactly("upms", "sample");
        assertThat(route(routes, "upms").getUri()).isEqualTo(URI.create("lb://mars-cloud-upms-service"));
        assertThat(route(routes, "sample").getUri()).isEqualTo(URI.create("lb://mars-cloud-sample-service"));
        assertThat(route(routes, "upms").getPredicate().toString()).contains("/upms/**");
        assertThat(route(routes, "sample").getPredicate().toString()).contains("/sample/**");
    }

    private static Route route(List<Route> routes, String id) {
        return routes.stream().filter(route -> id.equals(route.getId())).findFirst()
                .orElseThrow(() -> new AssertionError("路由表里没有 id 为 " + id + " 的路由"));
    }

    @Test
    void discoveryLocatorStaysOff() {
        // 打开后注册中心里的每个服务都会被自动暴露，网关就不再是「显式声明的唯一入口」。
        assertThat(environment.getProperty(
                "spring.cloud.gateway.server.webflux.discovery.locator.enabled", Boolean.class, false))
                .isFalse();
    }

    @Test
    void gatewayIsReactiveOnPortEightOneHundred() {
        assertThat(environment.getProperty("spring.main.web-application-type")).isEqualTo("reactive");
        assertThat(environment.getProperty("server.port")).isEqualTo("8100");
    }
}
