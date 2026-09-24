package com.mars.cloud.service.auth.infrastructure.sms;

import com.mars.cloud.service.auth.configuration.AuthProperties;
import com.mars.cloud.service.auth.configuration.AuthEnvironment;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods=false)
public class SmsSenderConfiguration {
    @Bean
    @ConditionalOnProperty(prefix="mars.auth.sms",name="mock-enabled",havingValue="true")
    SmsSender mockSmsSender(AuthProperties properties,AuthEnvironment environment) {
        if (!environment.local()) throw new IllegalStateException("Mock SMS requires local/test");
        return new MockSmsSender(properties);
    }

    @Bean
    @ConditionalOnMissingBean(SmsSender.class)
    SmsSender unavailableSmsSender() { return new UnavailableSmsSender(); }
}
