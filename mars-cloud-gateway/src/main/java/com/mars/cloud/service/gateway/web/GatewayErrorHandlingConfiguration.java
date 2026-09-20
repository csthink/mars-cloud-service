package com.mars.cloud.service.gateway.web;

import com.mars.cloud.service.gateway.env.EnvProfilesProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.codec.ServerCodecConfigurer;

/**
 * 注册信封式异常处理器，替换 Spring Boot 默认的 {@code DefaultErrorWebExceptionHandler}。
 *
 * <p>Boot 的默认处理器排序为 -1，且在容器里已有 {@link ErrorWebExceptionHandler} 时自动退让；
 * 这里给 -2 保证先于它生效。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EnvProfilesProperties.class)
public class GatewayErrorHandlingConfiguration {

    @Bean
    ErrorEnvelopeResolver errorEnvelopeResolver() {
        return new ErrorEnvelopeResolver();
    }

    @Bean
    @Order(-2)
    ErrorWebExceptionHandler envelopeErrorWebExceptionHandler(ErrorEnvelopeResolver resolver,
                                                              MessageSource messageSource,
                                                              Environment environment,
                                                              EnvProfilesProperties envProfiles,
                                                              ServerCodecConfigurer codecConfigurer) {
        return new EnvelopeErrorWebExceptionHandler(resolver, messageSource, environment, envProfiles, codecConfigurer);
    }
}
