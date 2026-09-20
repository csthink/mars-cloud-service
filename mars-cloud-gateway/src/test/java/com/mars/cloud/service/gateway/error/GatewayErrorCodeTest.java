package com.mars.cloud.service.gateway.error;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守护网关错误码：落在框架分配表的 gateway 段、彼此不重复、三份 i18n 都有文案。
 *
 * <p>框架的区间校验器在 Servlet 栈的 mvc starter 里，网关引不了，所以这里用测试补上同等约束。
 */
class GatewayErrorCodeTest {

    private static final List<String> BUNDLES = List.of(
            "i18n/error-code.properties",
            "i18n/error-code_zh_CN.properties",
            "i18n/error-code_en_US.properties");

    @Test
    void rangeMatchesFrameworkAllocationForGateway() {
        // 与框架 FrameworkErrorCodeRange 里 gateway 段的定义保持一致；改动分配表时这里会红。
        assertThat(GatewayErrorCode.RANGE_START).isEqualTo(63000);
        assertThat(GatewayErrorCode.RANGE_END).isEqualTo(63999);
    }

    @Test
    void everyCodeFallsInsideGatewayRange() {
        for (GatewayErrorCode code : GatewayErrorCode.values()) {
            assertThat(code.getCode())
                    .as("%s 必须落在 gateway 区间", code)
                    .isBetween(GatewayErrorCode.RANGE_START, GatewayErrorCode.RANGE_END);
        }
    }

    @Test
    void codesAreUnique() {
        long distinct = Arrays.stream(GatewayErrorCode.values()).mapToInt(GatewayErrorCode::getCode).distinct().count();
        assertThat(distinct).isEqualTo(GatewayErrorCode.values().length);
    }

    @Test
    void everyCodeHasMessageInAllBundles() throws IOException {
        for (String bundle : BUNDLES) {
            Properties properties = load(bundle);
            for (GatewayErrorCode code : GatewayErrorCode.values()) {
                assertThat(properties.getProperty(code.getMsgKey()))
                        .as("%s 缺少 %s 的文案", bundle, code)
                        .isNotBlank();
            }
            assertThat(properties.getProperty("error.code.500"))
                    .as("%s 缺少 500 兜底文案", bundle)
                    .isNotBlank();
        }
    }

    @Test
    void msgKeyFollowsFrameworkConvention() {
        assertThat(GatewayErrorCode.ROUTE_NOT_FOUND.getMsgKey()).isEqualTo("error.code.63001");
    }

    private static Properties load(String path) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            properties.load(in);
        }
        return properties;
    }
}
