package com.mars.cloud.service.monitor;

import de.codecentric.boot.admin.server.cloud.discovery.InstanceDiscoveryListener;
import de.codecentric.boot.admin.server.domain.entities.InstanceRepository;
import de.codecentric.boot.admin.server.services.InstanceRegistry;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 面板开始停机后不再做实例发现，并等进行中的一次发现结束。
 *
 * <p>停机时 Nacos 的优雅停机先注销面板并关闭 Nacos 客户端，再按
 * {@code spring.cloud.nacos.discovery.graceful-shutdown-wait-time}（默认 10 秒）等待，之后才进入组件停止阶段；
 * 按 {@code spring.cloud.nacos.discovery.watch-delay} 定时触发发现的发布器要到组件停止阶段才停。
 * 等待期间触发的发现会让 Nacos 客户端被重新创建，停机中途重新连接 Nacos，赶上进程退出时打出连接失败的 ERROR；
 * 这时的发现结果里也已经没有面板自己，面板会把自己当作被移除的实例。
 *
 * <p>所以在上下文关闭事件的最前面（早于 Nacos 的优雅停机）关上发现：关闭方拿写锁，等持有读锁的发现结束；
 * 之后的发现一律跳过。发现对注册中心的查询在调用线程上同步完成，读锁覆盖得到。
 */
class ShutdownAwareInstanceDiscoveryListener extends InstanceDiscoveryListener implements ApplicationContextAware {

    private final ReadWriteLock gate = new ReentrantReadWriteLock();

    /** 由 {@link #gate} 保护。 */
    private boolean stopped;

    private ApplicationContext context;

    ShutdownAwareInstanceDiscoveryListener(DiscoveryClient discoveryClient, InstanceRegistry registry,
                                           InstanceRepository repository) {
        super(discoveryClient, registry, repository);
    }

    @Override
    public void setApplicationContext(ApplicationContext context) {
        this.context = context;
    }

    @Override
    protected void discover() {
        gate.readLock().lock();
        try {
            if (!stopped) {
                super.discover();
            }
        } finally {
            gate.readLock().unlock();
        }
    }

    /** 只响应面板自己的上下文：管理端口的子上下文关闭时，事件也会传到这里。 */
    @EventListener
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void stopDiscovery(ContextClosedEvent event) {
        if (event.getApplicationContext() != context) {
            return;
        }
        gate.writeLock().lock();
        try {
            stopped = true;
        } finally {
            gate.writeLock().unlock();
        }
    }
}
