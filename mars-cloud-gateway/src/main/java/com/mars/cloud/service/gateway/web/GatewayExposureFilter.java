package com.mars.cloud.service.gateway.web;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

/** 对已匹配的路由检查 Host 与仅供本地测试的服务入口。 */
@Component
public final class GatewayExposureFilter implements GlobalFilter, Ordered {

    private final boolean localTest;

    public GatewayExposureFilter(Environment environment) {
        this.localTest = GatewayHostPolicy.isLocalTest(environment);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
        if (route == null) {
            return chain.filter(exchange);
        }
        String id = route.getId();
        boolean allowed = "auth-issuer".equals(id)
                ? GatewayHostPolicy.isAuthHost(exchange.getRequest(), localTest)
                : GatewayHostPolicy.isApiHost(exchange.getRequest(), localTest);
        if (("upms".equals(id) || "sample".equals(id)) && !localTest) {
            allowed = false;
        }
        if (!allowed) {
            return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "No matching gateway route"));
        }
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
