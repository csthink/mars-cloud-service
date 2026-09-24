package com.mars.cloud.service.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守护「配置项进版本库、环境取值只从环境变量来」这条约定。
 *
 * <p>{@code application-local.yml} 会进版本库，前提是它**不含任何环境相关的取值**。
 * 网关没有数据源；Redis 与 Nacos 的地址、Namespace、账号以及端口都不能写进 local profile，
 * 一旦有人把它们写回该文件，这里就会失败。
 *
 * <p>断言按 **key** 做，不做字符串匹配：注释里出现 "password" 或示例写法都不算违规。
 */
@SpringBootTest(classes = GatewayApplication.class)
@ActiveProfiles({"local", "test"})
class LocalConfigHygieneTest {

    @Autowired
    private Environment environment;

    @Test
    void localConfigDeclaresNoConnectionKeys() throws IOException {
        assertThat(nestedValue("spring", "cloud", "nacos")).isEmpty();
        assertThat(nestedValue("spring", "config", "import")).isEmpty();
        assertThat(nestedValue("spring", "data", "redis")).isEmpty();
        assertThat(nestedValue("server", "port")).isEmpty();
    }

    @Test
    void connectionKeysAreAbsentWithoutEnvironmentVariables() {
        // test profile 关闭了 Nacos 接入，非 test 段的连接键不应出现在环境里。
        assertThat(environment.containsProperty("spring.cloud.nacos.discovery.server-addr")).isFalse();
        assertThat(environment.containsProperty("spring.cloud.nacos.config.server-addr")).isFalse();
    }

    @Test
    void localProfileOnlyCarriesBehaviourSwitches() {
        assertThat(environment.getActiveProfiles()).contains("local");
        assertThat(environment.getProperty("spring.jackson.time-zone")).isEqualTo("Asia/Shanghai");
    }

    /** 按 YAML 展平后的属性名读取实际配置，不受注释内容影响。 */
    private static Optional<Object> nestedValue(String... path) throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application-local", new ClassPathResource("config/application-local.yml"));
        assertThat(sources).isNotEmpty();
        String key = String.join(".", path);
        return sources.stream().map(source -> source.getProperty(key)).filter(java.util.Objects::nonNull)
                .findFirst();
    }
}
