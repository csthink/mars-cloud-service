package com.mars.cloud.service.monitor;

import de.codecentric.boot.admin.server.domain.entities.InstanceRepository;
import de.codecentric.boot.admin.server.notify.DingTalkNotifier;
import de.codecentric.boot.admin.server.notify.LoggingNotifier;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 实例状态变化的通知：只有日志通知。
 *
 * <p>日志通知始终存在：它把状态变化写成一行日志，被日志采集一并收走，
 * 是验收与事后排查的依据。
 *
 * <p>钉钉群机器人通知暂不支持。面板自带的钉钉通知器把签名参数编码了两次，
 * 与钉钉要求的单次编码不一致。它按 {@code spring.boot.admin.notify.dingtalk.webhook-url}
 * 是否存在决定装配，所以只要配置了这个地址，面板就启动失败：
 * 装配出一个发不出去的通知器，会让人以为实例下线时有提醒。
 */
@Configuration(proxyBeanMethods = false)
public class MonitorNotificationConfiguration {

    static final String DINGTALK_UNSUPPORTED =
            "监控面板暂不支持钉钉通知，请删除 spring.boot.admin.notify.dingtalk.* 配置："
                    + "面板自带的钉钉通知器把签名参数编码了两次，与钉钉要求的单次编码不一致";

    @Bean
    LoggingNotifier marsMonitorLoggingNotifier(InstanceRepository repository) {
        return new LoggingNotifier(repository);
    }

    /** 按 bean 定义的类型判断，不实例化钉钉通知器。 */
    @Bean
    InitializingBean marsMonitorDingTalkNotifierGuard(ListableBeanFactory beans) {
        return () -> {
            if (beans.getBeanNamesForType(DingTalkNotifier.class, true, false).length > 0) {
                throw new IllegalStateException(DINGTALK_UNSUPPORTED);
            }
        };
    }
}
