package com.mars.cloud.service.upms;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class MarsCloudGroundingTest {

    private static final Path ROOT = Path.of("").toAbsolutePath();

    @Test
    void runtimeUsesMarsCloudPackageAndDoesNotRestoreLegacyUimsPackage() throws IOException {
        List<Path> mainFiles = javaMainFiles();

        assertThat(mainFiles).isNotEmpty();
        assertThat(join(mainFiles)).contains("package com.mars.cloud.service.upms");
        assertThat(join(mainFiles)).doesNotContain("com.csthink.uims");
    }

    @Test
    void userVerticalSliceIsNotPresentInRuntimeOrReadme() throws IOException {
        String runtimeText = join(javaMainFiles());
        String readme = Files.readString(ROOT.resolve("README.md"));

        assertThat(runtimeText)
                .doesNotContain("UserController")
                .doesNotContain("UserService")
                .doesNotContain("UserRepository")
                .doesNotContain("UserMapper")
                .doesNotContain("UserDTO")
                .doesNotContain("UserCreateReq");
        assertThat(readme).doesNotContain("/api/v1/users");
    }

    @Test
    void pdpPathDoesNotUseBusinessExceptionOrNumericUnifyResponseFail() throws IOException {
        String runtimeText = join(javaMainFiles());

        assertThat(runtimeText).doesNotContain("BusinessException");
        assertThat(runtimeText).doesNotContain("UnifyResponse.fail(");
    }

    @Test
    void configPreservesContextPathAndErrorCodeRangeButNotAsPublicCodes() throws IOException {
        String application = Files.readString(ROOT.resolve("src/main/resources/config/application.yml"));
        String runtimeText = join(javaMainFiles());

        assertThat(application).contains("context-path: /upms");
        // 归属名与区间以框架的权威分配表为准：upms-service 占 65000–65999。
        assertThat(application).contains("owner: upms-service");
        assertThat(application).contains("start: 65000");
        assertThat(application).contains("end: 65999");
        assertThat(runtimeText).contains("SNAPSHOT_UNAVAILABLE");
        assertThat(runtimeText).contains("snapshot_unavailable");
        assertThat(runtimeText).doesNotContain("code(\"503\")");
        assertThat(runtimeText).doesNotContain("code(\"650");
    }

    @Test
    void localProfileDisablesExternalInfraAutoConfigurationForPdpFoundation() throws IOException {
        String localProfile = Files.readString(ROOT.resolve("src/main/resources/config/application-local.yml"));

        // 只关掉本模块 classpath 上确实存在的自动装配。
        // Redisson / lock4j 的自动配置类不在 classpath 上（框架不引任何锁实现），无需排除。
        assertThat(localProfile)
                .contains("DataSourceAutoConfiguration")
                .contains("RedisAutoConfiguration")
                .contains("RedisRepositoriesAutoConfiguration")
                .doesNotContain("Redisson")
                .doesNotContain("baomidou");
    }

    private static List<Path> javaMainFiles() throws IOException {
        Path sourceRoot = ROOT.resolve("src/main/java");
        if (!Files.exists(sourceRoot)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            return paths.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        }
    }

    private static String join(List<Path> files) throws IOException {
        StringBuilder builder = new StringBuilder();
        for (Path file : files) {
            builder.append("\n--- ").append(ROOT.relativize(file)).append(" ---\n");
            builder.append(Files.readString(file));
        }
        return builder.toString();
    }
}
