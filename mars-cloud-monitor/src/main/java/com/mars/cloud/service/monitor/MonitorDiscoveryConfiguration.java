package com.mars.cloud.service.monitor;

import de.codecentric.boot.admin.server.cloud.discovery.ServiceInstanceConverter;
import de.codecentric.boot.admin.server.domain.entities.InstanceRepository;
import de.codecentric.boot.admin.server.services.InstanceRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 实例发现：用 {@link ShutdownAwareInstanceDiscoveryListener} 替换 Spring Boot Admin 自带的发现监听器。
 *
 * <p>自带的监听器在没有同类 bean 时才装配；这里的创建方式与它相同（同样的构造参数、实例转换器与
 * {@code spring.boot.admin.discovery.*} 配置绑定），关掉 {@code spring.boot.admin.discovery.enabled} 时同样不装配。
 */
@Configuration(proxyBeanMethods = false)
public class MonitorDiscoveryConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "spring.boot.admin.discovery", name = "enabled", matchIfMissing = true)
    @ConfigurationProperties(prefix = "spring.boot.admin.discovery")
    ShutdownAwareInstanceDiscoveryListener marsMonitorInstanceDiscoveryListener(
            ServiceInstanceConverter converter, DiscoveryClient discoveryClient,
            InstanceRegistry registry, InstanceRepository repository) {
        ShutdownAwareInstanceDiscoveryListener listener =
                new ShutdownAwareInstanceDiscoveryListener(discoveryClient, registry, repository);
        listener.setConverter(converter);
        return listener;
    }
}
