package com.mars.cloud.service.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 在 DEBUG 级别记录转发请求头的过滤器固定为 INFO：请求头里有 Authorization 的访问令牌。
 *
 * <p>可观测性验收把网关的包整体调到 DEBUG，并在推送日志前核对日志里没有测试令牌，那是真进程上的检查；
 * 本用例守住配置文件里的这一项。
 */
class RequestHeaderLoggingTest {

    private static final String OBSERVATION_FILTER_LEVEL =
            "logging.level.org.springframework.cloud.gateway.filter.headers.observation";

    @Test void theRequestHeaderObservationFilterIsPinnedToInfo() throws IOException {
        List<PropertySource<?>> documents = new YamlPropertySourceLoader()
                .load("gateway-configuration", new ClassPathResource("config/application.yml"));
        assertThat(documents.get(0).getProperty(OBSERVATION_FILTER_LEVEL)).isEqualTo("INFO");
    }
}
