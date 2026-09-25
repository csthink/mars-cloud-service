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
 * 测试用的规则来源：测试进程不连 Nacos，每个规则 Data ID 默认是 {@code []}（不限流）。
 *
 * <p>只在测试 classpath 上登记为自动装配，排在 Sentinel 组件之前，网关的全部用例都在 Sentinel 生效的状态下运行。
 * 每个 Spring 上下文一份内容与监听器：限流用例经注入的 {@link Rules} 只向自己的上下文下发规则，
 * 同一个测试进程里缓存的其他上下文收不到通知。
 */
@AutoConfiguration(before = {MarsSentinelGatewayAutoConfiguration.class, MarsSentinelAutoConfiguration.class})
public class TestSentinelRules {

    @Bean
    Rules testSentinelRules() {
        return new Rules();
    }

    /** 内存里的规则内容；{@link #publish} 像 Nacos 一样更新内容并通知监听器。 */
    public static final class Rules implements RuleConfigSource {

        private final Map<String, String> contents = new ConcurrentHashMap<>();
        private final Map<String, List<Consumer<String>>> listeners = new ConcurrentHashMap<>();

        public void publish(String dataId, String content) {
            contents.put(dataId, content);
            listeners.getOrDefault(dataId, List.of()).forEach(listener -> listener.accept(content));
        }

        @Override
        public String read(String dataId, String group, Duration timeout) {
            return contents.getOrDefault(dataId, "[]");
        }

        @Override
        public Registration listen(String dataId, String group, Consumer<String> listener) {
            listeners.computeIfAbsent(dataId, key -> new CopyOnWriteArrayList<>()).add(listener);
            return () -> listeners.getOrDefault(dataId, List.of()).remove(listener);
        }
    }
}
