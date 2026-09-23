package com.mars.cloud.service.monitor;

import com.alibaba.cloud.nacos.NacosDiscoveryProperties;
import com.alibaba.cloud.nacos.registry.NacosGracefulShutdownDelegate;
import de.codecentric.boot.admin.server.domain.entities.EventsourcingInstanceRepository;
import de.codecentric.boot.admin.server.domain.entities.InstanceRepository;
import de.codecentric.boot.admin.server.eventstore.InMemoryEventStore;
import de.codecentric.boot.admin.server.services.HashingInstanceUrlIdGenerator;
import de.codecentric.boot.admin.server.services.InstanceRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.client.discovery.event.HeartbeatEvent;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.support.GenericApplicationContext;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 面板停机时的实例发现：上下文开始关闭后不再查询注册中心，并等进行中的一次查询结束。
 * 原因见 {@link ShutdownAwareInstanceDiscoveryListener}。
 */
class MonitorDiscoveryShutdownTest {

    private final GenericApplicationContext context = new GenericApplicationContext();
    private final RecordingDiscoveryClient discoveryClient = new RecordingDiscoveryClient();
    private ShutdownAwareInstanceDiscoveryListener listener;

    @BeforeEach void createListener() {
        InstanceRepository repository = new EventsourcingInstanceRepository(new InMemoryEventStore());
        InstanceRegistry registry = new InstanceRegistry(repository, new HashingInstanceUrlIdGenerator(), instance -> true);
        listener = new ShutdownAwareInstanceDiscoveryListener(discoveryClient, registry, repository);
        listener.setApplicationContext(context);
    }

    @Test void discoveryRunsBeforeTheContextCloses() {
        listener.onApplicationEvent(new HeartbeatEvent(this, 1L));

        assertThat(discoveryClient.queries).hasValue(1);
    }

    @Test void noDiscoveryAfterTheContextStartsClosing() {
        listener.stopDiscovery(new ContextClosedEvent(context));
        listener.onApplicationEvent(new HeartbeatEvent(this, 1L));

        assertThat(discoveryClient.queries).hasValue(0);
    }

    /** 管理端口的子上下文关闭时，事件也会传到面板的上下文，不能因此停止发现。 */
    @Test void closingAnotherContextDoesNotStopDiscovery() {
        listener.stopDiscovery(new ContextClosedEvent(new GenericApplicationContext()));
        listener.onApplicationEvent(new HeartbeatEvent(this, 1L));

        assertThat(discoveryClient.queries).hasValue(1);
    }

    /** 关闭要等进行中的查询结束才返回，之后 Nacos 才关闭客户端，查询不会碰到已关闭的客户端。 */
    @Test void closingWaitsForTheDiscoveryInProgress() throws InterruptedException {
        discoveryClient.blockNextQuery();
        Thread discovery = new Thread(() -> listener.onApplicationEvent(new HeartbeatEvent(this, 1L)));
        discovery.start();
        discoveryClient.awaitQueryStarted();

        AtomicBoolean queryFinishedWhenClosed = new AtomicBoolean();
        Thread closer = new Thread(() -> {
            listener.stopDiscovery(new ContextClosedEvent(context));
            queryFinishedWhenClosed.set(discoveryClient.queryFinished.get());
        });
        closer.start();
        // 关闭若不等待，这里 closer 已经结束；若等待，它停在锁上。两种情况都不靠睡眠时长判断。
        await().atMost(Duration.ofSeconds(5))
                .until(() -> closer.getState() == Thread.State.WAITING || !closer.isAlive());
        discoveryClient.releaseQuery();
        closer.join(5000);
        discovery.join(5000);

        assertThat(queryFinishedWhenClosed).isTrue();
    }

    /**
     * 上下文关闭时，停止发现排在 Nacos 的优雅停机之前：优雅停机先注销并关闭客户端再等待，
     * 排在它之后的话，等待期间的发现会重新创建客户端。Nacos 的监听器没有声明顺序，
     * 本监听器靠 {@code @Order(HIGHEST_PRECEDENCE)} 排到前面；去掉这个声明，本用例失败。
     */
    @Test void discoveryStopsBeforeTheNacosGracefulShutdownRuns() {
        AnnotationConfigApplicationContext closing = new AnnotationConfigApplicationContext();
        AtomicBoolean discoveryStoppedFirst = new AtomicBoolean();
        closing.registerBean(NacosGracefulShutdownDelegate.class,
                () -> new NacosGracefulShutdownDelegate(null, new NacosDiscoveryProperties()) {
                    @Override
                    protected void doGracefulShutdown() {
                        closing.getBean(ShutdownAwareInstanceDiscoveryListener.class)
                                .onApplicationEvent(new HeartbeatEvent(this, 1L));
                        discoveryStoppedFirst.set(discoveryClient.queries.get() == 0);
                    }
                });
        closing.registerBean(ShutdownAwareInstanceDiscoveryListener.class, () -> {
            InstanceRepository repository = new EventsourcingInstanceRepository(new InMemoryEventStore());
            return new ShutdownAwareInstanceDiscoveryListener(discoveryClient,
                    new InstanceRegistry(repository, new HashingInstanceUrlIdGenerator(), instance -> true), repository);
        });
        closing.refresh();
        closing.close();

        assertThat(discoveryStoppedFirst).isTrue();
    }

    private static final class RecordingDiscoveryClient implements DiscoveryClient {

        final AtomicInteger queries = new AtomicInteger();
        final AtomicBoolean queryFinished = new AtomicBoolean();
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile boolean block;

        void blockNextQuery() {
            block = true;
        }

        void awaitQueryStarted() throws InterruptedException {
            assertThat(started.await(5, TimeUnit.SECONDS)).as("发现查询没有开始").isTrue();
        }

        void releaseQuery() {
            release.countDown();
        }

        @Override public String description() {
            return "recording";
        }

        @Override public List<ServiceInstance> getInstances(String serviceId) {
            return List.of();
        }

        @Override public List<String> getServices() {
            queries.incrementAndGet();
            started.countDown();
            if (block) {
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            queryFinished.set(true);
            return List.of();
        }
    }
}
