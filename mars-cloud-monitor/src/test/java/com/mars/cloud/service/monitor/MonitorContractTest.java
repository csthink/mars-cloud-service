package com.mars.cloud.service.monitor;

import de.codecentric.boot.admin.server.notify.DingTalkNotifier;
import de.codecentric.boot.admin.server.notify.LoggingNotifier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 面板的访问控制与通知装配。
 *
 * <p>管理端点与业务端点在这里共用一个端口，模拟请求才到得了；真实部署的分端口行为
 * 由可观测性组件自己的真端口用例覆盖。
 */
@SpringBootTest(properties = {
        "mars.monitor.admin.username=monitor-admin",
        "mars.monitor.admin.password=monitor-secret",
        "mars.observability.management.port-offset=0",
        "mars.observability.management.username=ops",
        "mars.observability.management.password=ops-secret"
})
@AutoConfigureMockMvc
@ActiveProfiles({"local", "test"})
class MonitorContractTest {

    @Autowired MockMvc mvc;
    @Autowired ApplicationContext context;

    /** 浏览器未登录访问面板会被送到登录页，而不是直接看到实例列表。 */
    @Test void unauthenticatedBrowserIsRedirectedToTheLoginPage() throws Exception {
        mvc.perform(get("/applications").accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection());
    }

    /**
     * 非浏览器的未登录请求得到 401 而不是重定向：入口点按请求头做内容协商，
     * 面板同时开了表单登录与 Basic 认证，两条路径都要钉住。
     */
    @Test void unauthenticatedApiCallIsRejected() throws Exception {
        mvc.perform(get("/applications").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    /** 登录页本身必须匿名可达，否则谁也登不进来。 */
    @Test void theLoginPageIsReachableAnonymously() throws Exception {
        mvc.perform(get("/login")).andExpect(status().isOk());
    }

    /** 带正确凭据时可以读实例列表。 */
    @Test void instancesAreReadableWithCorrectCredentials() throws Exception {
        mvc.perform(get("/applications").with(org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.httpBasic("monitor-admin", "monitor-secret")))
                .andExpect(status().isOk());
    }

    /** 日志通知始终装配：实例状态变化要留下可检索的痕迹。 */
    @Test void theLoggingNotifierIsAlwaysPresent() {
        assertThat(context.getBeansOfType(LoggingNotifier.class)).isNotEmpty();
    }

    /** 钉钉通知暂不支持，默认不装配；配置了地址时启动失败，见 {@link MonitorStartupFailureTest}。 */
    @Test void theDingTalkNotifierIsAbsent() {
        assertThat(context.getBeansOfType(DingTalkNotifier.class)).isEmpty();
    }

    /**
     * 面板版本必须跟随 Boot 基线。构建期的依赖禁止规则是第一道，
     * 这一条从运行时的 classpath 再确认一次，避免规则被绕过后没人发现。
     */
    @Test void theAdminServerStaysOnTheFourZeroLine() throws IOException {
        Properties properties = new Properties();
        try (InputStream stream = LoggingNotifier.class.getResourceAsStream(
                "/META-INF/maven/de.codecentric/spring-boot-admin-server/pom.properties")) {
            assertThat(stream).as("读不到监控面板的版本信息").isNotNull();
            properties.load(stream);
        }
        assertThat(properties.getProperty("version")).startsWith("4.0.");
    }
}
