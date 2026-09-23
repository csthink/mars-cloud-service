package com.mars.cloud.service.monitor;

import de.codecentric.boot.admin.server.domain.entities.Instance;
import de.codecentric.boot.admin.server.domain.entities.InstanceRepository;
import de.codecentric.boot.admin.server.domain.events.InstanceDeregisteredEvent;
import de.codecentric.boot.admin.server.domain.events.InstanceEvent;
import de.codecentric.boot.admin.server.notify.LoggingNotifier;

/**
 * 在状态变化之外，实例被移除时也写一行日志通知。
 *
 * <p>实例停止或崩溃时从 Nacos 注销，面板的发现刷新随后把它移除。发现刷新按
 * {@code spring.cloud.nacos.discovery.watch-delay} 的间隔运行，状态轮询按
 * {@code spring.boot.admin.monitor.status-interval} 的间隔运行，两者互不等待，
 * 移除可能先于状态轮询发生。{@link LoggingNotifier} 只处理状态变化事件，这时实例从面板消失，
 * 却没有任何通知。本类让移除事件也写一行，形如
 * {@code Instance mars-cloud-sample-service (13631fc3079b) DEREGISTERED}；
 * 被移除的实例仍保留注册信息，所以能写出服务名。其他事件（如注册）照旧不写。
 */
class DeregistrationAwareLoggingNotifier extends LoggingNotifier {

    DeregistrationAwareLoggingNotifier(InstanceRepository repository) {
        super(repository);
    }

    @Override
    protected boolean shouldNotify(InstanceEvent event, Instance instance) {
        return event instanceof InstanceDeregisteredEvent || super.shouldNotify(event, instance);
    }
}
