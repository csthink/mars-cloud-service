package com.mars.cloud.service.monitor;

import de.codecentric.boot.admin.server.config.EnableAdminServer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 运行中实例的监控面板。
 *
 * <p>实例由注册中心发现，被监控的服务不需要装客户端，也不需要改任何配置；
 * 本部署物只绑内网地址，不经网关，也不在网关路由表里。
 */
@SpringBootApplication
@EnableAdminServer
public class MonitorApplication {

    public static void main(String[] args) {
        SpringApplication.run(MonitorApplication.class, args);
    }
}
