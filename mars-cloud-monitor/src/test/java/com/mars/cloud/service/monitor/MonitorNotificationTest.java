package com.mars.cloud.service.monitor;

import de.codecentric.boot.admin.server.domain.entities.EventsourcingInstanceRepository;
import de.codecentric.boot.admin.server.domain.entities.Instance;
import de.codecentric.boot.admin.server.domain.events.InstanceDeregisteredEvent;
import de.codecentric.boot.admin.server.domain.events.InstanceRegisteredEvent;
import de.codecentric.boot.admin.server.domain.events.InstanceStatusChangedEvent;
import de.codecentric.boot.admin.server.domain.values.InstanceId;
import de.codecentric.boot.admin.server.domain.values.Registration;
import de.codecentric.boot.admin.server.domain.values.StatusInfo;
import de.codecentric.boot.admin.server.eventstore.InMemoryEventStore;
import de.codecentric.boot.admin.server.notify.LoggingNotifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 面板的日志通知：实例状态变化与实例被移除都写一行。
 *
 * <p>实例停止或崩溃时会从 Nacos 注销，面板的发现刷新随后把它移除。发现刷新与状态轮询各按固定间隔运行，
 * 移除可能先于状态轮询发生；Spring Boot Admin 自带的日志通知只处理状态变化，这时实例从面板消失却没有
 * 任何通知。所以移除本身也要写一行。
 */
@ExtendWith(OutputCaptureExtension.class)
class MonitorNotificationTest {

    private static final InstanceId ID = InstanceId.of("abc123");

    private EventsourcingInstanceRepository repository;
    private LoggingNotifier notifier;

    @BeforeEach void registerOneInstance() {
        repository = new EventsourcingInstanceRepository(new InMemoryEventStore());
        repository.save(Instance.create(ID).register(Registration
                        .create("mars-cloud-sample-service", "http://127.0.0.1:9203/actuator/health")
                        .build()))
                .block();
        notifier = new MonitorNotificationConfiguration().marsMonitorLoggingNotifier(repository);
    }

    @Test void removalFromTheRegistryIsLogged(CapturedOutput output) {
        Instance removed = repository.find(ID).block().deregister();
        repository.save(removed).block();

        notifier.notify(new InstanceDeregisteredEvent(ID, removed.getVersion())).block();

        assertThat(output).contains("Instance mars-cloud-sample-service (abc123) DEREGISTERED");
    }

    @Test void statusChangesAreStillLogged(CapturedOutput output) {
        Instance offline = repository.find(ID).block().withStatusInfo(StatusInfo.ofOffline());
        repository.save(offline).block();

        notifier.notify(new InstanceStatusChangedEvent(ID, offline.getVersion(), StatusInfo.ofOffline())).block();

        assertThat(output).contains("Instance mars-cloud-sample-service (abc123) is OFFLINE");
    }

    /** 只多处理移除这一种事件：注册等其他事件照旧不写，否则每次发现新实例都会多一行。 */
    @Test void registrationIsNotLogged(CapturedOutput output) {
        Instance instance = repository.find(ID).block();

        notifier.notify(new InstanceRegisteredEvent(ID, instance.getVersion(), instance.getRegistration())).block();

        assertThat(output).doesNotContain("Instance mars-cloud-sample-service (abc123)");
    }
}
