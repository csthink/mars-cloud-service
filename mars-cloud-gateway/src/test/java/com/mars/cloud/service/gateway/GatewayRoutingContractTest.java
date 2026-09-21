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
    void upmsRouteTargetsServiceNameThroughLoadBalancer() {
        List<Route> routes = routeLocator.getRoutes().collectList().block();

        assertThat(routes).isNotNull();
        assertThat(routes).extracting(Route::getId).containsExactly("upms");
        assertThat(routes.get(0).getUri()).isEqualTo(URI.create("lb://mars-cloud-upms-service"));
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
