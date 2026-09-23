package com.mars.cloud.service.monitor;

import de.codecentric.boot.admin.server.config.EnableAdminServer;
import org.springframework.boot.LazyInitializationExcludeFilter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.Set;

/**
 * 运行中实例的监控面板。
 *
 * <p>实例由注册中心发现，被监控的服务不需要装客户端，也不需要改任何配置；
 * 本部署物只绑内网地址，不经网关，也不在网关路由表里。
 */
@SpringBootApplication
@EnableAdminServer
public class MonitorApplication {

    /** 启动期检查的 bean：管理员账号、读取实例的凭据、钉钉通知。 */
    private static final Set<String> STARTUP_CHECKS = Set.of(
            "marsMonitorAdminAccount", "marsMonitorInstanceCredentialsGuard", "marsMonitorDingTalkNotifierGuard");

    public static void main(String[] args) {
        SpringApplication.run(MonitorApplication.class, args);
    }

    /**
     * 打开延迟初始化时，启动期检查仍在启动时创建：没有别的 bean 依赖它们，延迟后检查会静默跳过。
     * 声明为静态方法，筛选器在其他 bean 处理之前就能取到。
     */
    @Bean
    static LazyInitializationExcludeFilter marsMonitorStartupChecksExcludedFromLazyInitialization() {
        return (beanName, definition, beanType) -> STARTUP_CHECKS.contains(beanName);
    }
}
