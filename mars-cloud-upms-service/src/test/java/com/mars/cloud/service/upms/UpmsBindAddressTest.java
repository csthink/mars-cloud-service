package com.mars.cloud.service.upms;

import com.mars.cloud.nacos.autoconfigure.MarsNacosDefaultsEnvironmentPostProcessor;
import com.mars.cloud.observability.autoconfigure.MarsObservabilityDefaultsEnvironmentPostProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
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
 * UPMS 的业务端口、管理端口与注册到 Nacos 的地址都取 {@code SERVER_ADDRESS}，缺省为回环地址。
 *
 * <p>配置文件只写 {@code server.address}；管理端口的地址与注册地址由可观测性组件与 Nacos 组件从它推导。
 * Spring Boot 的管理端口不继承 {@code server.address}，Spring Cloud Alibaba 也不按它选注册地址，
 * 少了推导时管理端口绑定全部网卡、注册地址是网卡地址。用例读本模块配置文件的全部文档，
 * 加真实的环境变量属性源，再运行两个组件的环境后处理器，断言三个地址一致；
 * 配置文件若另写了其中一个键而取值不同，用例同样失败。
 */
class UpmsBindAddressTest {

    private static final List<String> ADDRESS_KEYS =
            List.of("server.address", "management.server.address", "spring.cloud.nacos.discovery.ip");

    @Test void everyAddressDefaultsToTheLoopbackAddress() throws IOException {
        assertThat(resolveAddresses(Map.of())).allSatisfy((key, value) -> assertThat(value).as(key).isEqualTo("127.0.0.1"));
    }

    @Test void everyAddressFollowsServerAddress() throws IOException {
        assertThat(resolveAddresses(Map.of("SERVER_ADDRESS", "10.1.2.3")))
                .allSatisfy((key, value) -> assertThat(value).as(key).isEqualTo("10.1.2.3"));
    }

    /** 配置文件的每个文档都参与解析：注册中心接入所在的文档只在非测试 profile 生效，而它正是部署时用的那份。 */
    private static Map<String, String> resolveAddresses(Map<String, Object> variables) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        MutablePropertySources sources = environment.getPropertySources();
        sources.replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables));
        new YamlPropertySourceLoader()
                .load("upms-configuration", new ClassPathResource("config/application.yml"))
                .forEach(sources::addLast);
        SpringApplication application = new SpringApplication();
        new MarsObservabilityDefaultsEnvironmentPostProcessor().postProcessEnvironment(environment, application);
        new MarsNacosDefaultsEnvironmentPostProcessor().postProcessEnvironment(environment, application);
        Map<String, String> addresses = new LinkedHashMap<>();
        for (String key : ADDRESS_KEYS) {
            addresses.put(key, environment.getProperty(key));
        }
        return addresses;
    }
}
