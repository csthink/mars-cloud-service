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
        // 路由 id 按顺序全等：新增、删除或调换顺序都必须先让这条用例失败，再由改动者确认。
        // 顺序也是契约的一部分：网关按声明顺序匹配，重排会改变路径重叠时的命中结果。
        assertThat(routes).extracting(Route::getId).containsExactly("auth-issuer", "auth-api", "product", "order", "notice", "upms", "sample");
        assertThat(route(routes, "auth-issuer").getUri()).isEqualTo(URI.create("lb://mars-cloud-auth-service"));
        assertThat(route(routes, "auth-api").getUri()).isEqualTo(URI.create("lb://mars-cloud-auth-service"));
        assertThat(route(routes, "product").getUri()).isEqualTo(URI.create("lb://mars-cloud-product-service"));
        assertThat(route(routes, "order").getUri()).isEqualTo(URI.create("lb://mars-cloud-order-service"));
        assertThat(route(routes, "notice").getUri()).isEqualTo(URI.create("lb://mars-cloud-notice-service"));
        assertThat(route(routes, "upms").getUri()).isEqualTo(URI.create("lb://mars-cloud-upms-service"));
        assertThat(route(routes, "sample").getUri()).isEqualTo(URI.create("lb://mars-cloud-sample-service"));
        // 断言判定的完整描述而不是子串：给某条路由偷偷再加一个判定（Header、Method 等）也会让这里失败。
        // 描述里带一个路由定位器包装的 lambda，它的名字含每次运行都不同的地址，先归一化再比较。
        assertThat(predicateOf(routes, "auth-issuer"))
                .isEqualTo("(<locator> && Paths: [/oauth2/**, /.well-known/**, /login, /login/**, /logout, /connect/logout, /userinfo], match trailing slash: true)");
        assertThat(predicateOf(routes, "auth-api"))
                .isEqualTo("(<locator> && Paths: [/auth/**], match trailing slash: true)");
        assertThat(predicateOf(routes, "product"))
                .isEqualTo("(<locator> && Paths: [/product/**], match trailing slash: true)");
        assertThat(predicateOf(routes, "order"))
                .isEqualTo("(<locator> && Paths: [/order/**], match trailing slash: true)");
        assertThat(predicateOf(routes, "notice"))
                .isEqualTo("(<locator> && Paths: [/notice/**], match trailing slash: true)");
        assertThat(predicateOf(routes, "upms"))
                .isEqualTo("(<locator> && Paths: [/upms/**], match trailing slash: true)");
        assertThat(predicateOf(routes, "sample"))
                .isEqualTo("(<locator> && Paths: [/sample/**], match trailing slash: true)");
    }

    private static String predicateOf(List<Route> routes, String id) {
        return route(routes, id).getPredicate().toString()
                .replaceAll("RouteDefinitionRouteLocator\\$\\$Lambda/0x[0-9a-f]+", "<locator>");
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
