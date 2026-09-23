package com.mars.cloud.service.monitor;

import com.jayway.jsonpath.JsonPath;
import de.codecentric.boot.admin.server.cloud.discovery.InstanceDiscoveryListener;
import de.codecentric.boot.admin.server.notify.DingTalkNotifier;
import de.codecentric.boot.admin.server.notify.LoggingNotifier;
import de.codecentric.boot.admin.server.ui.config.AdminServerUiProperties;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
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

    /** 登录页模板把请求里的 CSRF 令牌以 JSON 对象内联进脚本：{@code var csrf = {...};}。 */
    private static final Pattern RENDERED_CSRF = Pattern.compile("var csrf = (\\{.*?\\});");

    @Autowired MockMvc mvc;
    @Autowired ApplicationContext context;

    /** 浏览器未登录访问面板会被送到登录页，而不是直接看到实例列表。 */
    @Test void unauthenticatedBrowserIsRedirectedToTheLoginPage() throws Exception {
        mvc.perform(get("/applications").accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    /**
     * 表单登录带 CSRF 令牌与正确口令时成功，随后回到 redirectTo 指定的面板页面。
     * 面板前端传的是当前页面的完整地址，本站的完整地址与站内路径都接受。
     */
    @Test void formLoginReturnsToTheRequestedPage() throws Exception {
        mvc.perform(login().param("redirectTo", "/applications"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/applications"));
        mvc.perform(login().param("redirectTo", "http://localhost/applications"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/applications"));
    }

    /** redirectTo 指向别的站点时不跟随，回到面板首页：否则登录页可以被用来把管理员带到任意站点。 */
    @Test void formLoginIgnoresARedirectToAnotherSite() throws Exception {
        for (String target : new String[] {"https://attacker.example/", "//attacker.example/", "/\\attacker.example/",
                "http://localhost:8080/applications", "javascript:alert(1)"}) {
            mvc.perform(login().param("redirectTo", target))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/"));
        }
    }

    /** 面板不接受手动登记实例：实例只经 Nacos 发现。 */
    @Test void manualInstanceRegistrationIsRejected() throws Exception {
        mvc.perform(post("/instances").with(admin()).with(uiCsrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"manual\",\"healthUrl\":\"http://127.0.0.1:1/actuator/health\"}"))
                .andExpect(status().isForbidden());
    }

    /** 面板对实例的操作（例如改日志级别）要带 CSRF 令牌：登录后的管理员被诱导发起的跨站请求不能生效。 */
    @Test void instanceOperationsRequireTheCsrfToken() throws Exception {
        mvc.perform(post("/instances/unknown/actuator/loggers/ROOT").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    /**
     * 面板前端的两种提交方式都要能通过 CSRF 校验：异步请求把 XSRF-TOKEN Cookie 的原值放进 X-XSRF-TOKEN 请求头，
     * 登出菜单是普通表单，把同一个原值放进 _csrf 表单字段、不带请求头。令牌因此要以前端可读的 Cookie 下发，
     * 请求头与表单字段里的原值都要被接受。首页加载的 sba-settings.js 请求会下发 Cookie。
     */
    @Test void theUiCanSendTheTokenItReadsFromTheCookie() throws Exception {
        Cookie token = mvc.perform(get("/sba-settings.js").with(admin()))
                .andExpect(status().isOk())
                .andExpect(cookie().httpOnly("XSRF-TOKEN", false))
                .andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(token).isNotNull();
        mvc.perform(post("/logout").cookie(token)).andExpect(status().isForbidden());
        // 异步请求的做法；CSRF 校验通过后登出照常处理，响应码由登出配置决定，这里只核对没有因为令牌被拒。
        mvc.perform(post("/logout").cookie(token).header("X-XSRF-TOKEN", token.getValue()))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(403));
    }

    /**
     * 令牌必须与 Cookie 一致：请求头或表单字段换成别的值都被拒绝。对照请求带一致的请求头，通过 CSRF 校验；
     * 用登出请求核对，因为模拟请求走不到面板转发实例请求的那一段，校验规则对所有修改请求相同。
     */
    @Test void aTokenThatDiffersFromTheCookieIsRejected() throws Exception {
        Cookie token = mvc.perform(get("/login")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(token).as("登录页的响应应当下发 XSRF-TOKEN Cookie").isNotNull();
        String other = "other-" + token.getValue();
        mvc.perform(post("/logout").cookie(token).header("X-XSRF-TOKEN", other)).andExpect(status().isForbidden());
        mvc.perform(post("/logout").cookie(token).param("_csrf", other)).andExpect(status().isForbidden());
        mvc.perform(post("/logout").cookie(token).header("X-XSRF-TOKEN", token.getValue()))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(403));
    }

    /** 登出菜单的做法：表单字段 _csrf 带 Cookie 原值、不带请求头，登出成功后回到登录页。 */
    @Test void theLogoutFormOfTheUiEndsTheSession() throws Exception {
        MockHttpSession session = (MockHttpSession) mvc.perform(login().param("redirectTo", "/applications"))
                .andExpect(status().is3xxRedirection())
                .andReturn().getRequest().getSession(false);
        assertThat(session).isNotNull();
        Cookie token = mvc.perform(get("/sba-settings.js").session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(token).isNotNull();
        mvc.perform(post("/logout").session(session).cookie(token).param("_csrf", token.getValue())
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?logout"));
        mvc.perform(get("/applications").session(session).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    /**
     * 登录成功后令牌会更换、旧 Cookie 被删除；之后的任何请求（包括不读令牌的数据接口）都要重新下发 Cookie，
     * 否则前端接下来的修改请求没有令牌可带。Spring Security 只在读取令牌时写 Cookie，靠面板的令牌加载过滤器做到。
     */
    @Test void everyRequestAfterLoginIssuesAFreshTokenCookie() throws Exception {
        MvcResult login = mvc.perform(login()).andExpect(status().is3xxRedirection()).andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        assertThat(session).isNotNull();
        Cookie token = mvc.perform(get("/applications").session(session).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(token).isNotNull();
        assertThat(token.getValue()).isNotEmpty();
    }

    /** 安全链没有配置「记住我」，登录页不显示这个选项。 */
    @Test void rememberMeIsNotOffered() {
        assertThat(context.getBean(AdminServerUiProperties.class).isRememberMeEnabled()).isFalse();
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
        mvc.perform(get("/applications").with(admin())).andExpect(status().isOk());
    }

    private static RequestPostProcessor admin() {
        return httpBasic("monitor-admin", "monitor-secret");
    }

    /**
     * 按登录页表单的做法提交：令牌取自登录页渲染进页面的 csrf 对象，放在 _csrf 表单字段里，
     * 同时带上登录页下发的 XSRF-TOKEN Cookie。
     */
    private MockHttpServletRequestBuilder login() throws Exception {
        MvcResult page = mvc.perform(get("/login")).andExpect(status().isOk()).andReturn();
        Cookie cookie = page.getResponse().getCookie("XSRF-TOKEN");
        assertThat(cookie).as("登录页的响应应当下发 XSRF-TOKEN Cookie").isNotNull();
        Matcher rendered = RENDERED_CSRF.matcher(page.getResponse().getContentAsString());
        assertThat(rendered.find()).as("登录页应当把 csrf 对象渲染进页面").isTrue();
        String token = JsonPath.read(rendered.group(1), "$.token");
        return post("/login").cookie(cookie).param("_csrf", token)
                .param("username", "monitor-admin").param("password", "monitor-secret");
    }


    /**
     * 按面板前端异步请求的做法带 CSRF 令牌：先从登录页的响应取 XSRF-TOKEN Cookie，再把原值放进 X-XSRF-TOKEN 请求头。
     * 不用 Spring Security 测试工具的 csrf()：它会把共享上下文里的令牌存储换成会话存储，之后的请求不再下发 Cookie。
     */
    private RequestPostProcessor uiCsrfToken() throws Exception {
        Cookie token = mvc.perform(get("/login")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(token).as("登录页的响应应当下发 XSRF-TOKEN Cookie").isNotNull();
        return request -> {
            request.setCookies(token);
            request.addHeader("X-XSRF-TOKEN", token.getValue());
            return request;
        };
    }

    /**
     * 日志通知始终装配，且只有一个：实例状态变化与移除都要留下可检索的痕迹，
     * 两个日志通知器会把每条通知写两遍。
     */
    @Test void exactlyOneDeregistrationAwareLoggingNotifierIsPresent() {
        assertThat(context.getBeansOfType(LoggingNotifier.class).values())
                .singleElement()
                .isInstanceOf(DeregistrationAwareLoggingNotifier.class);
    }

    /**
     * 实例发现只有一个监听器，是停机时会先停下的那个；Spring Boot Admin 自带的同类监听器不再装配，
     * 否则它在停机等待期间照常发现，Nacos 客户端被重新创建。
     */
    @Test void exactlyOneShutdownAwareDiscoveryListenerIsPresent() {
        assertThat(context.getBeansOfType(InstanceDiscoveryListener.class).values())
                .singleElement()
                .isInstanceOf(ShutdownAwareInstanceDiscoveryListener.class);
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
