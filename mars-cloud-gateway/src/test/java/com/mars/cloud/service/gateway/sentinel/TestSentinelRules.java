package com.mars.cloud.service.gateway.sentinel;

import com.mars.cloud.sentinel.autoconfigure.MarsSentinelAutoConfiguration;
import com.mars.cloud.sentinel.autoconfigure.MarsSentinelGatewayAutoConfiguration;
import com.mars.cloud.sentinel.rule.RuleConfigSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 测试用的规则来源：测试进程不连 Nacos，每个规则 Data ID 默认是 {@code []}（不限流）；
 * 限流用例经 {@link #publish} 下发规则，结束时 {@link #reset} 清空，避免影响复用的其他上下文。
 *
 * <p>只在测试 classpath 上登记为自动装配，排在 Sentinel 组件之前，网关的全部用例都在 Sentinel 生效的状态下运行。
 */
@AutoConfiguration(before = {MarsSentinelGatewayAutoConfiguration.class, MarsSentinelAutoConfiguration.class})
public class TestSentinelRules {

    private static final Map<String, String> CONTENTS = new ConcurrentHashMap<>();
    private static final Map<String, List<Consumer<String>>> LISTENERS = new ConcurrentHashMap<>();

    @Bean
    RuleConfigSource testSentinelRuleConfigSource() {
        return new InMemorySource();
    }

    /** 像 Nacos 一样更新内容并通知监听器。 */
    public static void publish(String dataId, String content) {
        CONTENTS.put(dataId, content);
        LISTENERS.getOrDefault(dataId, List.of()).forEach(listener -> listener.accept(content));
    }

    /** 把下发过的规则恢复为 {@code []}。 */
    public static void reset() {
        for (String dataId : List.copyOf(CONTENTS.keySet())) {
            publish(dataId, "[]");
        }
        CONTENTS.clear();
    }

    private static final class InMemorySource implements RuleConfigSource {

        @Override
        public String read(String dataId, String group, Duration timeout) {
            return CONTENTS.getOrDefault(dataId, "[]");
        }

        @Override
        public Registration listen(String dataId, String group, Consumer<String> listener) {
            LISTENERS.computeIfAbsent(dataId, key -> new CopyOnWriteArrayList<>()).add(listener);
            return () -> LISTENERS.getOrDefault(dataId, List.of()).remove(listener);
        }
    }
}
