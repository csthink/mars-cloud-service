package com.mars.cloud.service.gateway.web;

import com.mars.cloud.security.SecurityErrorCode;
import com.mars.cloud.security.reactive.ReactiveSecurityErrors;
import com.mars.cloud.service.gateway.security.GatewaySessionRevocation;
import java.util.Optional;
import java.util.function.Predicate;
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Checks the verified JWT's session before a routed API request reaches its upstream: a missing or malformed
 * {@code sid} is an invalid token, a revoked {@code sid} is a revoked session.
 */
final class GatewaySessionRevocationWebFilter implements WebFilter {
    private final Predicate<ServerHttpRequest> apiRequest;
    private final GatewaySessionRevocation revocation;
    private final ReactiveSecurityErrors securityErrors;
    private final ErrorWebExceptionHandler gatewayErrors;

    GatewaySessionRevocationWebFilter(Predicate<ServerHttpRequest> apiRequest,
                                      GatewaySessionRevocation revocation,
                                      ReactiveSecurityErrors securityErrors,
                                      ErrorWebExceptionHandler gatewayErrors) {
        this.apiRequest = apiRequest;
        this.revocation = revocation;
        this.securityErrors = securityErrors;
        this.gatewayErrors = gatewayErrors;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!apiRequest.test(exchange.getRequest())) {
            return chain.filter(exchange);
        }
        return ReactiveSecurityContextHolder.getContext()
                .flatMap(context -> Mono.justOrEmpty(context.getAuthentication()))
                .filter(authentication -> authentication instanceof JwtAuthenticationToken && authentication.isAuthenticated())
                .cast(JwtAuthenticationToken.class)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(authentication -> {
                    if (authentication.isEmpty()) {
                        return chain.filter(exchange);
                    }
                    Object claim = authentication.get().getToken().getClaim("sid");
                    String sid = claim instanceof String value ? value : null;
                    if (!GatewaySessionRevocation.validSid(sid)) {
                        return securityErrors.write(exchange, SecurityErrorCode.TOKEN_INVALID);
                    }
                    return revocation.isRevoked(sid)
                            .flatMap(revoked -> revoked
                                    ? securityErrors.write(exchange, SecurityErrorCode.SESSION_REVOKED)
                                    : chain.filter(exchange))
                            .onErrorResume(GatewaySessionRevocation.StoreUnavailableException.class,
                                    failure -> gatewayErrors.handle(exchange, failure));
                });
    }
}
