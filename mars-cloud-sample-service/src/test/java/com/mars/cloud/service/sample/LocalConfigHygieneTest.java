package com.mars.cloud.service.sample;

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
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守护「配置项进版本库、环境取值只从环境变量来」这条约定。
 *
 * <p>{@code application-local.yml} 会进版本库，前提是它**不含任何环境相关的取值**。
 * 一旦有人把 host / 端口 / 库名 / 口令写回该文件，这里就会失败。
 *
 * <p>断言按 **key** 做，不做字符串匹配：注释里出现 "password" 或示例写法都不算违规。
 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
class LocalConfigHygieneTest extends com.mars.cloud.service.sample.security.SecurityTestSupport {

    @Autowired
    private Environment environment;

    @Test
    void localConfigDeclaresNoConnectionKeys() throws IOException {
        assertThat(nestedValue("spring", "datasource", "url")).isEmpty();
        assertThat(nestedValue("spring", "datasource", "username")).isEmpty();
        assertThat(nestedValue("spring", "datasource", "password")).isEmpty();
        assertThat(nestedValue("spring", "data", "redis", "host")).isEmpty();
        assertThat(nestedValue("spring", "data", "redis", "port")).isEmpty();
        assertThat(nestedValue("spring", "data", "redis", "password")).isEmpty();
    }

    @Test
    void connectionKeysAreAbsentWithoutEnvironmentVariables() {
        assertThat(environment.containsProperty("spring.datasource.url")).isFalse();
        assertThat(environment.containsProperty("spring.data.redis.host")).isFalse();
    }

    @Test
    void localProfileIsActiveByDefault() {
        assertThat(environment.getActiveProfiles()).contains("local");
        assertThat(environment.getProperty("spring.jackson.time-zone")).isEqualTo("Asia/Shanghai");
    }

    /**
     * 按路径读 {@code application-local.yml} 里显式声明的值；未声明返回空。
     * 只看真实的 key，不受注释内容影响。
     */
    @SuppressWarnings("unchecked")
    private static Optional<Object> nestedValue(String... path) throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application-local", new ClassPathResource("config/application-local.yml"));
        assertThat(sources).isNotEmpty();

        Object current = sources.get(0).getSource();
        for (String segment : path) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(segment)) {
                return Optional.empty();
            }
            current = ((Map<String, Object>) map).get(segment);
        }
        return Optional.ofNullable(current);
    }
}
