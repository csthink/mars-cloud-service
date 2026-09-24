package com.mars.cloud.service.gateway.web;

import com.mars.cloud.security.reactive.MarsReactiveSecurityConfigurer;
import com.mars.cloud.security.reactive.ReactiveSecurityErrors;
import com.mars.cloud.service.gateway.security.GatewaySessionRevocation;
import com.github.benmanes.caffeine.cache.Ticker;
import java.util.List;
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/** Applies JWT verification to routed business requests while leaving unmatched requests to Gateway's 404 handler. */
@Configuration(proxyBeanMethods = false)
public class GatewaySecurityConfiguration {
    private static final String[] ISSUER_PATTERNS = {
            "/oauth2/**", "/.well-known/**", "/login", "/login/**", "/logout", "/connect/logout", "/userinfo"};
    private static final List<PathPattern> ISSUER_PATHS = patterns(ISSUER_PATTERNS);
    private static final List<PathPattern> API_PATHS = patterns(
            "/auth/**", "/product/**", "/order/**", "/notice/**");
    private static final List<PathPattern> LOCAL_API_PATHS = patterns("/upms/**", "/sample/**");

    @Bean
    GatewaySessionRevocation gatewaySessionRevocation(ReactiveStringRedisTemplate redis) {
        return new GatewaySessionRevocation(redis, Ticker.systemTicker());
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 200)
    SecurityWebFilterChain gatewaySecurityWebFilterChain(ServerHttpSecurity http,
                                                          MarsReactiveSecurityConfigurer configurer,
                                                          GatewaySessionRevocation revocation,
                                                          ReactiveSecurityErrors securityErrors,
                                                          GatewayAdminIpAllowlist adminIpAllowlist,
                                                          ErrorWebExceptionHandler gatewayErrors,
                                                          Environment environment) {
        boolean localTest = GatewayHostPolicy.isLocalTest(environment);
        ServerWebExchangeMatcher routedRequest = exchange -> {
            var request = exchange.getRequest();
            PathContainer path = request.getPath().pathWithinApplication();
            boolean issuer = GatewayHostPolicy.isAuthHost(request, localTest) && matches(ISSUER_PATHS, path);
            boolean api = isApiRequest(request, path, localTest);
            return issuer || api ? ServerWebExchangeMatcher.MatchResult.match()
                    : ServerWebExchangeMatcher.MatchResult.notMatch();
        };
        http.securityMatcher(routedRequest)
                .csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .requestCache(cache -> cache.disable())
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .pathMatchers(ISSUER_PATTERNS).permitAll()
                        .pathMatchers("/order/v1/callbacks/**").permitAll()
                        .pathMatchers("/product/v1/me", "/product/v1/me/**", "/product/v1/admin", "/product/v1/admin/**",
                                "/notice/v1/me", "/notice/v1/me/**", "/notice/v1/admin", "/notice/v1/admin/**")
                        .authenticated()
                        .pathMatchers(HttpMethod.GET, "/product/**", "/notice/**").permitAll()
                        .anyExchange().authenticated())
                .addFilterBefore(new GatewaySessionRevocationWebFilter(
                        request -> isApiRequest(request, request.getPath().pathWithinApplication(), localTest),
                        revocation, securityErrors, gatewayErrors), SecurityWebFiltersOrder.AUTHORIZATION)
                .addFilterAfter(new GatewayAdminAccessWebFilter(adminIpAllowlist, securityErrors),
                        SecurityWebFiltersOrder.AUTHORIZATION);
        return configurer.configure(http).build();
    }

    private static boolean isApiRequest(ServerHttpRequest request, PathContainer path, boolean localTest) {
        return GatewayHostPolicy.isApiHost(request, localTest)
                && (matches(API_PATHS, path) || (localTest && matches(LOCAL_API_PATHS, path)));
    }

    private static List<PathPattern> patterns(String... values) {
        return java.util.Arrays.stream(values).map(PathPatternParser.defaultInstance::parse).toList();
    }

    private static boolean matches(List<PathPattern> patterns, PathContainer path) {
        return patterns.stream().anyMatch(pattern -> pattern.matches(path));
    }
}
