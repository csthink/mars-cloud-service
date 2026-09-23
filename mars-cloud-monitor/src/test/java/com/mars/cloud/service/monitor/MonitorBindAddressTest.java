package com.mars.cloud.service.monitor;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 面板的业务端口、管理端口与注册到 Nacos 的地址都取 {@code SERVER_ADDRESS}。
 *
 * <p>Spring Boot 的管理端口按 {@code management.server.address} 绑定，没有配置时绑定全部网卡，
 * 不继承 {@code server.address}。注册地址与绑定地址不一致时，面板按注册地址读取自己的管理端点会连不上，
 * 把自己显示为离线。用例读面板的配置文件，按真实的环境变量属性源解析三个键。
 */
class MonitorBindAddressTest {

    private static final List<String> ADDRESS_KEYS =
            List.of("server.address", "management.server.address", "spring.cloud.nacos.discovery.ip");

    @Test void everyAddressDefaultsToTheLoopbackAddress() throws IOException {
        assertThat(resolveAddresses(Map.of())).allSatisfy((key, value) -> assertThat(value).as(key).isEqualTo("127.0.0.1"));
    }

    @Test void everyAddressFollowsServerAddress() throws IOException {
        assertThat(resolveAddresses(Map.of("SERVER_ADDRESS", "10.1.2.3")))
                .allSatisfy((key, value) -> assertThat(value).as(key).isEqualTo("10.1.2.3"));
    }

    /** 配置文件的每个文档都参与解析：注册地址所在的文档只在非测试 profile 生效，而它正是部署时用的那份。 */
    private static Map<String, String> resolveAddresses(Map<String, Object> variables) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        MutablePropertySources sources = environment.getPropertySources();
        sources.replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables));
        new YamlPropertySourceLoader()
                .load("monitor-configuration", new ClassPathResource("config/application.yml"))
                .forEach(sources::addLast);
        Map<String, String> addresses = new LinkedHashMap<>();
        for (String key : ADDRESS_KEYS) {
            addresses.put(key, environment.getProperty(key));
        }
        return addresses;
    }
}
