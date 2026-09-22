package com.mars.cloud.service.monitor;

import de.codecentric.boot.admin.server.domain.entities.InstanceRepository;
import de.codecentric.boot.admin.server.notify.LoggingNotifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 实例状态变化的通知。
 *
 * <p>日志通知始终存在：它把状态变化写成一行日志，被日志采集一并收走，
 * 是验收与事后排查的依据。
 *
 * <p>群机器人通知由面板自带的实现提供，这里不重复写，配置也不写进本模块的默认值：
 * 它按属性是否存在决定装配，给一个空字符串会让它装配出来却发不出去，
 * 加签时还会因为空密钥报错。需要它的环境直接用完整属性名配置
 * {@code spring.boot.admin.notify.dingtalk.webhook-url}（以及需要加签时的 {@code secret}）。
 */
@Configuration(proxyBeanMethods = false)
public class MonitorNotificationConfiguration {

    @Bean
    LoggingNotifier marsMonitorLoggingNotifier(InstanceRepository repository) {
        return new LoggingNotifier(repository);
    }
}
