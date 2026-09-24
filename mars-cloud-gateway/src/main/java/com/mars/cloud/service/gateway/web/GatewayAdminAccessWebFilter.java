package com.mars.cloud.service.gateway.web;

import com.mars.cloud.security.SecurityErrorCode;
import com.mars.cloud.security.reactive.ReactiveSecurityErrors;
import java.util.Optional;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

/** Requires a verified console token and an approved canonical client IP for admin routes. */
final class GatewayAdminAccessWebFilter implements WebFilter {
    private static final PathPattern ADMIN_PATH = PathPatternParser.defaultInstance.parse("/*/v1/admin/**");
    private final GatewayAdminIpAllowlist allowlist;
    private final ReactiveSecurityErrors errors;

    GatewayAdminAccessWebFilter(GatewayAdminIpAllowlist allowlist, ReactiveSecurityErrors errors) {
        this.allowlist = allowlist;
        this.errors = errors;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (exchange.getRequest().getMethod() == HttpMethod.OPTIONS) return chain.filter(exchange);
        PathContainer path = exchange.getRequest().getPath().pathWithinApplication();
        if (!ADMIN_PATH.matches(path)) return chain.filter(exchange);
        return ReactiveSecurityContextHolder.getContext()
                .flatMap(context -> Mono.justOrEmpty(context.getAuthentication()))
                .filter(authentication -> authentication instanceof JwtAuthenticationToken && authentication.isAuthenticated())
                .cast(JwtAuthenticationToken.class)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(authentication -> {
                    if (authentication.isEmpty()) return errors.write(exchange, SecurityErrorCode.ACCESS_DENIED);
                    Object clientId = authentication.get().getToken().getClaim("client_id");
                    String address = exchange.getAttribute(GatewayIngressFilter.CLIENT_IP_ATTRIBUTE);
                    return "console".equals(clientId) && allowlist.allows(address)
                            ? chain.filter(exchange)
                            : errors.write(exchange, SecurityErrorCode.ACCESS_DENIED);
                });
    }
}
