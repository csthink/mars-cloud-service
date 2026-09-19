package com.mars.cloud.service.sample.error;

import com.mars.cloud.mvc.autoconfigure.ErrorCodeRegistrarAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守护本服务的错误码区间声明。
 *
 * <p>这些用例是**反证**：只验证「示例能启动」无法区分
 * 「校验器在工作」与「校验器根本没装上」。所以每条都故意给非法配置，
 * 断言上下文**确实启动失败**。
 *
 * <p>用的是框架真实的自动配置（{@link ErrorCodeRegistrarAutoConfiguration}），
 * 不是手搓装配——否则测的就不是实际生效的那条链路了。
 */
class ErrorCodeRangeGuardTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ErrorCodeRegistrarAutoConfiguration.class));

    @Test
    void declaredRangeOutsideAuthoritySegmentFailsStartup() {
        runner.withPropertyValues(
                        "mars.error-code.framework-layers[0]=common",
                        "mars.error-code.ranges[0].owner=business",
                        // business 的权威区段是 66000–99999
                        "mars.error-code.ranges[0].start=1000",
                        "mars.error-code.ranges[0].end=1999")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void unknownOwnerFailsStartup() {
        runner.withPropertyValues(
                        "mars.error-code.ranges[0].owner=no-such-owner",
                        "mars.error-code.ranges[0].start=66100",
                        "mars.error-code.ranges[0].end=66199")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void reversedRangeFailsStartup() {
        runner.withPropertyValues(
                        "mars.error-code.ranges[0].owner=business",
                        "mars.error-code.ranges[0].start=66199",
                        "mars.error-code.ranges[0].end=66100")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void overlappingRangesFailStartup() {
        runner.withPropertyValues(
                        "mars.error-code.ranges[0].owner=business",
                        "mars.error-code.ranges[0].start=66100",
                        "mars.error-code.ranges[0].end=66199",
                        "mars.error-code.ranges[1].owner=business",
                        "mars.error-code.ranges[1].start=66150",
                        "mars.error-code.ranges[1].end=66299")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void declaredRangeOfThisServiceIsAccepted() {
        // 与 config/application.yml 保持一致；改坏了这里会红。
        runner.withPropertyValues(
                        "mars.error-code.framework-layers[0]=common",
                        "mars.error-code.framework-layers[1]=mvc",
                        "mars.error-code.ranges[0].owner=business",
                        "mars.error-code.ranges[0].start=66100",
                        "mars.error-code.ranges[0].end=66199")
                .run(context -> assertThat(context).hasNotFailed());
    }
}
