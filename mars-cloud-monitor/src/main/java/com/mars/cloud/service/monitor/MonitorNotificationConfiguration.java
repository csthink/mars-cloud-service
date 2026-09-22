package com.mars.cloud.service.monitor;

import de.codecentric.boot.admin.server.domain.entities.InstanceRepository;
import de.codecentric.boot.admin.server.notify.LoggingNotifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 实例状态变化的通知。
 *
 * <p>日志通知始终存在：它把状态变化写成一行日志，被日志采集一并收走，
 * 是验收与事后排查的依据。群机器人通知由 webhook 地址决定是否装配，
 * 那一条由面板自带的实现提供，这里不重复写。
 */
@Configuration(proxyBeanMethods = false)
public class MonitorNotificationConfiguration {

    @Bean
    LoggingNotifier marsMonitorLoggingNotifier(InstanceRepository repository) {
        return new LoggingNotifier(repository);
    }
}
