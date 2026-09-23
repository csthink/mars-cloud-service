package com.mars.cloud.service.monitor;

import de.codecentric.boot.admin.server.config.AdminServerProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * 面板自身的访问控制：单个管理员账号、表单登录。
 *
 * <p>账号由环境变量给出，任一为空就启动失败：面板能读到全部实例的管理端点，
 * 用默认口令或匿名开放等于把它们一并放开。
 */
@Configuration(proxyBeanMethods = false)
public class MonitorSecurityConfiguration {

    /** 读取实例管理端点的凭据为空时的启动失败消息。 */
    static final String INSTANCE_CREDENTIALS_MISSING = "监控面板读取实例管理端点的凭据不能为空："
            + "MARS_MANAGEMENT_USERNAME 与 MARS_MANAGEMENT_PASSWORD 都要给出非空值";

    /** 面板管理员账号，来自环境变量 MONITOR_USERNAME 与 MONITOR_PASSWORD。 */
    @ConfigurationProperties(prefix = "mars.monitor.admin")
    public static class AdminAccount implements InitializingBean {

        private String username;
        private String password;

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        @Override
        public void afterPropertiesSet() {
            if (username == null || username.isBlank() || password == null || password.isBlank()) {
                throw new IllegalStateException(
                        "监控面板必须配置管理员账号：mars.monitor.admin.username 与 password 都不能为空");
            }
        }
    }

    @Bean
    AdminAccount marsMonitorAdminAccount() {
        return new AdminAccount();
    }

    /**
     * 面板读取实例管理端点时带的凭据，来自 MARS_MANAGEMENT_USERNAME 与 MARS_MANAGEMENT_PASSWORD。
     * 环境变量存在但为空时占位符照样解析成空串，面板能启动，却读不到实例需要认证的端点，所以按缺失处理、拒绝启动。
     * 配置里没有这两项时（测试 profile）不检查。
     */
    @Bean
    InitializingBean marsMonitorInstanceCredentialsGuard(AdminServerProperties admin) {
        return () -> {
            AdminServerProperties.InstanceAuthProperties auth = admin.getInstanceAuth();
            if (auth.isEnabled() && (blank(auth.getDefaultUserName()) || blank(auth.getDefaultPassword()))) {
                throw new IllegalStateException(INSTANCE_CREDENTIALS_MISSING);
            }
        };
    }

    /** 配置了但只有空白字符；没有配置（null）不算。 */
    private static boolean blank(String value) {
        return value != null && value.isBlank();
    }

    @Bean
    PasswordEncoder marsMonitorPasswordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    InMemoryUserDetailsManager marsMonitorUserDetailsManager(AdminAccount account, PasswordEncoder encoder) {
        return new InMemoryUserDetailsManager(User.withUsername(account.getUsername())
                .password(encoder.encode(account.getPassword()))
                .authorities("MONITOR_ADMIN")
                .build());
    }

    @Bean
    SecurityFilterChain marsMonitorSecurityFilterChain(HttpSecurity http,
                                                       AdminServerProperties admin) throws Exception {
        String contextPath = admin.getContextPath();
        return http
                .authorizeHttpRequests(requests -> requests
                        // 面板自己的静态资源与登录页必须匿名可达，否则登录页加载不出来。
                        .requestMatchers(contextPath + "/assets/**").permitAll()
                        .requestMatchers(contextPath + "/login").permitAll()
                        // Spring Boot Admin 在这里接受实例自行登记。本面板的实例只经 Nacos 发现，不开放这条路径。
                        .requestMatchers(HttpMethod.POST, contextPath + "/instances").denyAll()
                        .anyRequest().authenticated())
                .formLogin(login -> login.loginPage(contextPath + "/login")
                        .successHandler(new SameOriginRedirectSuccessHandler(contextPath + "/")))
                .logout(logout -> logout.logoutUrl(contextPath + "/logout"))
                .httpBasic(basic -> { })
                // 面板前端从 XSRF-TOKEN Cookie 读出令牌原值：异步请求放进 X-XSRF-TOKEN 请求头，登出菜单放进 _csrf 表单字段。
                // 所以令牌以前端可读的 Cookie 下发，请求头与表单字段都按原值校验；对实例的操作同样要带令牌。
                // 不用 spa()：它把表单字段当作经过掩码的令牌解码，登出菜单提交的原值通不过校验。
                .csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
                .build();
    }

    /**
     * 每个请求都加载一次 CSRF 令牌，让 Cookie 始终存在。
     *
     * <p>Spring Security 只在有人访问令牌时才生成它并写 Cookie，面板的数据接口与静态资源都不访问令牌。
     * 登录成功后令牌会被更换、旧 Cookie 被删除；不主动加载的话，前端在下一次打开访问令牌的页面之前发出的修改请求
     * 都会因为没有令牌被拒绝。这个过滤器让登录换令牌之后的第一个请求就把新令牌写回 Cookie。
     */
    static final class CsrfCookieFilter extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (token != null) {
                token.getToken();
            }
            chain.doFilter(request, response);
        }
    }

    /**
     * 登录成功后回到 redirectTo 指定的页面，但只接受本站地址：站内路径，或协议、主机与端口都与本次请求相同的完整地址。
     * 面板前端传的是当前页面的完整地址。其他取值回到面板首页，登录页因此不能被用来把管理员带到别的站点。
     */
    static final class SameOriginRedirectSuccessHandler extends SavedRequestAwareAuthenticationSuccessHandler {

        SameOriginRedirectSuccessHandler(String defaultTargetUrl) {
            setTargetUrlParameter("redirectTo");
            setDefaultTargetUrl(defaultTargetUrl);
        }

        @Override
        protected String determineTargetUrl(HttpServletRequest request, HttpServletResponse response) {
            String target = super.determineTargetUrl(request, response);
            return sameOrigin(target, request) ? target : getDefaultTargetUrl();
        }

        private static boolean sameOrigin(String target, HttpServletRequest request) {
            URI uri;
            try {
                uri = new URI(target);
            }
            catch (URISyntaxException invalid) {
                return false;
            }
            if (uri.getScheme() == null && uri.getRawAuthority() == null) {
                return target.startsWith("/");
            }
            return request.getScheme().equalsIgnoreCase(uri.getScheme())
                    && request.getServerName().equalsIgnoreCase(uri.getHost())
                    && request.getServerPort() == effectivePort(uri);
        }

        private static int effectivePort(URI uri) {
            if (uri.getPort() != -1) {
                return uri.getPort();
            }
            return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        }
    }
}
