package com.mars.cloud.service.upms.infrastructure.snapshot;

import com.mars.cloud.service.upms.domain.policy.Grant;
import com.mars.cloud.service.upms.domain.policy.PlatformRoleKey;
import com.mars.cloud.service.upms.domain.policy.RoleDefinition;
import com.mars.cloud.service.upms.domain.snapshot.DecisionSnapshot;
import com.mars.cloud.service.upms.domain.snapshot.PlatformRegistry;
import com.mars.cloud.service.upms.infrastructure.registry.RegistryDefinition;
import com.mars.cloud.service.upms.infrastructure.registry.RegistryFixtureLoader;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * local profile 的**开发种子快照**：进程起来后立刻发布一份活动快照。
 *
 * <p>没有它，运行态的 UPMS 永远是「无活动快照」——所有决策请求都返回 503，
 * 成功路径（信封 + allow/deny）只能靠 {@code @SpringBootTest} 证明，无法在真进程里验收。
 *
 * <p>只在 {@code mars.upms.local-fixture.enabled=true} 时生效，默认关闭；
 * {@code application-local.yml} 把它打开。真实快照装载（注册中心 / 数据库）是后续阶段的事，
 * 届时本种子应被替换而不是叠加。
 */
@Configuration(proxyBeanMethods = false)
public class LocalSnapshotSeedConfiguration {

    private static final String FIXTURE = "fixtures/registries/demo-v1.yml";

    /** 种子里获得该平台全部能力的演示主体。 */
    private static final String SEED_SUBJECT = "local-admin";

    private static final String SEED_ROLE = "LocalAdmin";

    @Bean
    @ConditionalOnProperty(prefix = "mars.upms.local-fixture", name = "enabled", havingValue = "true")
    ApplicationRunner localSnapshotSeedRunner(InMemorySnapshotProvider provider) {
        return args -> provider.publishCandidate(seedSnapshot());
    }

    private static DecisionSnapshot seedSnapshot() {
        RegistryDefinition registry = new RegistryFixtureLoader()
                .load(new ClassPathResource(FIXTURE));
        PlatformRegistry platform = registry.toPlatformRegistry();

        List<Grant> grants = registry.resources().stream()
                .flatMap(item -> registry.actions().stream()
                        .map(action -> new Grant(registry.platform(), action, item.id())))
                .toList();
        PlatformRoleKey key = new PlatformRoleKey(registry.platform(), SEED_ROLE);

        return new DecisionSnapshot(
                "local-fixture",
                List.of(platform),
                List.of(new RoleDefinition(key, grants)),
                Map.of(SEED_SUBJECT, Set.of(key))
        );
    }
}
