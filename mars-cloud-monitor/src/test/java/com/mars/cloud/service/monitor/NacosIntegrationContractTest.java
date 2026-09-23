package com.mars.cloud.service.monitor;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class NacosIntegrationContractTest {

    @Test
    void applicationUsesRequiredNacosImportsInOrder() throws IOException {
        String application = new ClassPathResource("config/application.yml")
                .getContentAsString(StandardCharsets.UTF_8);
        String sharedImport = "nacos:shared-common.yaml?group=COMMON&refreshEnabled=true";
        String applicationImport =
                "nacos:${spring.application.name}.yaml?group=DEFAULT_GROUP&refreshEnabled=true";

        assertThat(application)
                .contains("name: mars-cloud-monitor")
                .contains("on-profile: \"!test\"")
                .contains("namespace: ${NACOS_NAMESPACE_ID}")
                .contains("group: DEFAULT_GROUP")
                .contains(sharedImport)
                .contains(applicationImport)
                .doesNotContain("optional:nacos:");
        assertThat(application.indexOf(sharedImport)).isLessThan(application.indexOf(applicationImport));
    }

    @Test
    void testRuntimeDisablesConfigAndDiscoveryTogether() throws IOException {
        String testConfiguration = new ClassPathResource("application.properties")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(testConfiguration)
                .contains("spring.profiles.active=local,test")
                .contains("spring.cloud.nacos.config.enabled=false")
                .contains("spring.cloud.nacos.config.import-check.enabled=false")
                .contains("spring.cloud.nacos.discovery.enabled=false");
    }

    /**
     * 容器入口必须带 {@code --sun-misc-unsafe-memory-access=allow} 与 {@code --enable-native-access=ALL-UNNAMED}。
     *
     * <p>前者：nacos-client 3.1.1 内部 shade 的 Guava 调用 {@code sun.misc.Unsafe}，JDK 24 起默认在
     * 首次调用时打印弃用警告（JEP 498）。测试进程明确离线、不会触发它，所以这条契约无法靠
     * 「启动日志里没有警告」在 CI 里直接验证；退而守住启动命令本身：谁改写 Dockerfile 时把参数
     * 丢了，这里就红。
     *
     * <p>后者：面板经 Reactor Netty 读取各实例的管理端点，Netty 加载平台原生库时触发 JDK 24 起的
     * restricted method 警告（JEP 472）。
     *
     * <p>{@code mvn spring-boot:run} 与测试 JVM 的同一组参数由 BOM 的 pluginManagement 统一给出。
     */
    @Test
    void containerEntrypointCarriesRequiredJvmFlags() throws IOException {
        String dockerfile = Files.readString(Path.of("Dockerfile"), StandardCharsets.UTF_8);
        String entrypoint = dockerfile.lines()
                .filter(line -> line.startsWith("ENTRYPOINT"))
                .reduce((first, second) -> {
                    throw new AssertionError("Dockerfile 只能有一个 ENTRYPOINT");
                })
                .orElseThrow(() -> new AssertionError("Dockerfile 缺少 ENTRYPOINT"));

        assertThat(entrypoint)
                .contains("\"--sun-misc-unsafe-memory-access=allow\"")
                .contains("\"--enable-native-access=ALL-UNNAMED\"")
                .contains("\"-jar\"");
        int jar = entrypoint.indexOf("\"-jar\"");
        assertThat(entrypoint.indexOf("--sun-misc-unsafe-memory-access=allow"))
                .as("JVM 参数必须写在 -jar 之前，否则会被当成应用参数")
                .isLessThan(jar);
        assertThat(entrypoint.indexOf("--enable-native-access=ALL-UNNAMED"))
                .as("JVM 参数必须写在 -jar 之前，否则会被当成应用参数")
                .isLessThan(jar);
    }
}
