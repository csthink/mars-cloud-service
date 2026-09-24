package com.mars.cloud.service.gateway;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/** Keeps routing and envelope contract tests focused on Gateway behavior without changing the packaged application. */
@Configuration(proxyBeanMethods = false)
@Profile("test & !security-integration")
class GatewayRouteTestSecurityConfiguration {
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 150)
    SecurityWebFilterChain routeContractSecurityWebFilterChain(ServerHttpSecurity http) {
        return http.csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .authorizeExchange(exchanges -> exchanges.anyExchange().permitAll())
                .build();
    }
}
