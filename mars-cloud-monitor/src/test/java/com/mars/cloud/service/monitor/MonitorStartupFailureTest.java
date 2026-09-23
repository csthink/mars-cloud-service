package com.mars.cloud.service.monitor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 面板在配置不可用时拒绝启动，而不是带着错误配置运行。
 *
 * <p>每条用例真实启动一次面板，业务端口取随机值，管理端点与业务端点共用端口。
 * Spring Boot 对任何启动失败都打一条通用的 ERROR，日志检查不能为它登记例外：那会放行所有启动失败。
 * 所以用例关掉这一个 logger，失败原因由断言核对。
 */
class MonitorStartupFailureTest {

    private static final String[] VALID = {
            "server.port=0",
            "logging.level.org.springframework.boot.SpringApplication=off",
            "mars.monitor.admin.username=monitor-admin",
            "mars.monitor.admin.password=monitor-secret",
            "mars.observability.management.port-offset=0",
            "mars.observability.management.username=ops",
            "mars.observability.management.password=ops-secret"
    };

    /** 对照：有效配置能启动，下面几条的失败因此只能来自各自改动的那一项。 */
    @Test void startsWithAValidConfiguration() {
        assertThatNoException().isThrownBy(() -> start());
    }

    /** 对照：读取实例的凭据都有值时能启动，凭据检查只拒绝空白值。 */
    @Test void startsWithNonBlankInstanceCredentials() {
        assertThatNoException().isThrownBy(() -> start(
                "spring.boot.admin.instance-auth.default-user-name=ops",
                "spring.boot.admin.instance-auth.default-password=ops-secret"));
    }

    /** 对照：打开延迟初始化、配置有效时能启动，下一条的失败因此只能来自各自改动的那一项。 */
    @Test void startsWithLazyInitializationAndAValidConfiguration() {
        assertThatNoException().isThrownBy(() -> start("spring.main.lazy-initialization=true",
                "spring.boot.admin.instance-auth.default-user-name=ops",
                "spring.boot.admin.instance-auth.default-password=ops-secret"));
    }

    /**
     * 打开延迟初始化时三项启动检查照样执行：它们没有被别的 bean 依赖，延迟后会静默跳过。
     * 每条都核对失败消息，一个无关的启动失败不能让用例通过。
     */
    @ParameterizedTest
    @MethodSource("startupChecks")
    void startupChecksRunEvenWithLazyInitialization(String invalid, String message) {
        assertThatThrownBy(() -> start("spring.main.lazy-initialization=true",
                "spring.boot.admin.instance-auth.default-user-name=ops",
                "spring.boot.admin.instance-auth.default-password=ops-secret", invalid))
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(message);
    }

    static Stream<Arguments> startupChecks() {
        return Stream.of(
                Arguments.of("mars.monitor.admin.password=", "mars.monitor.admin.username 与 password 都不能为空"),
                Arguments.of("spring.boot.admin.instance-auth.default-password=",
                        MonitorSecurityConfiguration.INSTANCE_CREDENTIALS_MISSING),
                Arguments.of("spring.boot.admin.notify.dingtalk.webhook-url=",
                        MonitorNotificationConfiguration.DINGTALK_UNSUPPORTED));
    }

    /** 管理员口令为空时面板会把全部实例的管理端点放开，所以拒绝启动。 */
    @Test void refusesToStartWithoutAnAdminPassword() {
        assertThatThrownBy(() -> start("mars.monitor.admin.password="))
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mars.monitor.admin.username 与 password 都不能为空");
    }

    /**
     * 读取实例管理端点的凭据为空时拒绝启动：环境变量存在但为空，占位符照样解析成空串，面板能启动，
     * 却读不到实例需要认证的端点。
     */
    @ParameterizedTest
    @ValueSource(strings = {"default-user-name", "default-password"})
    void refusesToStartWhenAnInstanceCredentialIsBlank(String blank) {
        assertThatThrownBy(() -> start(
                "spring.boot.admin.instance-auth.default-user-name=ops",
                "spring.boot.admin.instance-auth.default-password=ops-secret",
                "spring.boot.admin.instance-auth." + blank + "="))
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(MonitorSecurityConfiguration.INSTANCE_CREDENTIALS_MISSING);
    }

    /**
     * 钉钉通知暂不支持：配置了地址就拒绝启动，不装配一个发不出去的通知器。
     * 空值也算配置：面板自带的装配条件只看这个属性是否存在。
     */
    @ParameterizedTest
    @ValueSource(strings = {"http://127.0.0.1:9/hook", ""})
    void refusesToStartWhenADingTalkWebhookIsConfigured(String webhookUrl) {
        assertThatThrownBy(() -> start("spring.boot.admin.notify.dingtalk.webhook-url=" + webhookUrl))
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(MonitorNotificationConfiguration.DINGTALK_UNSUPPORTED);
    }

    /**
     * 属性以命令行参数传入。{@code SpringApplicationBuilder#properties} 设的是优先级最低的默认属性，
     * 会被 {@code config/application.yml} 里取环境变量的空值覆盖，用例就测不到自己改的那一项。
     * 同名参数给两次时 Spring 会用逗号连接两个值，所以先按属性名合并，改动项替换有效值。
     */
    private static void start(String... overrides) {
        Map<String, String> properties = new LinkedHashMap<>();
        Stream.concat(Arrays.stream(VALID), Arrays.stream(overrides)).forEach(property -> {
            int separator = property.indexOf('=');
            properties.put(property.substring(0, separator), property.substring(separator + 1));
        });
        String[] arguments = properties.entrySet().stream()
                .map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
                .toArray(String[]::new);
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(MonitorApplication.class)
                .profiles("local", "test")
                .run(arguments)) {
            assertThat(context.isActive()).isTrue();
        }
    }
}
