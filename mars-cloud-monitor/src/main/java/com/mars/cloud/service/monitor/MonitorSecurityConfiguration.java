package com.mars.cloud.service.monitor;

import de.codecentric.boot.admin.server.config.AdminServerProperties;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;

/**
 * 面板自身的访问控制：单个管理员账号、表单登录。
 *
 * <p>账号由环境变量给出，任一为空就启动失败：面板能读到全部实例的管理端点，
 * 用默认口令或匿名开放等于把它们一并放开。
 */
@Configuration(proxyBeanMethods = false)
public class MonitorSecurityConfiguration {

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
        SavedRequestAwareAuthenticationSuccessHandler success = new SavedRequestAwareAuthenticationSuccessHandler();
        success.setTargetUrlParameter("redirectTo");
        success.setDefaultTargetUrl(contextPath + "/");

        return http
                .authorizeHttpRequests(requests -> requests
                        // 面板自己的静态资源与登录页必须匿名可达，否则登录页加载不出来。
                        .requestMatchers(contextPath + "/assets/**").permitAll()
                        .requestMatchers(contextPath + "/login").permitAll()
                        .anyRequest().authenticated())
                .formLogin(login -> login.loginPage(contextPath + "/login").successHandler(success))
                .logout(logout -> logout.logoutUrl(contextPath + "/logout"))
                .httpBasic(basic -> { })
                // 被监控实例经这些路径上报与拉取，它们不带 CSRF 令牌。
                .csrf(csrf -> csrf.ignoringRequestMatchers(
                        contextPath + "/instances/**", contextPath + "/actuator/**"))
                .build();
    }
}
