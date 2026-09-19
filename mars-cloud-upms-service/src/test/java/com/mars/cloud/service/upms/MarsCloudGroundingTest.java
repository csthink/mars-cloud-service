package com.mars.cloud.service.upms;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
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
    void noInternalProjectNamesOrMilestoneNumberingLeakIntoThisPublicModule() throws IOException {
        String shippedSources = join(javaMainFiles());
        String shippedResources = join(textFilesUnder("src/main/resources"));
        List<Path> testFiles;
        try (Stream<Path> paths = Files.walk(ROOT.resolve("src/test/java"))) {
            testFiles = paths.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        }
        List<Path> testResourceFiles = textFilesUnder("src/test/resources");

        // 本仓是公开仓：已停止的外部项目名与内部里程碑/验收门编号都不该出现在这里。
        //
        // 覆盖面刻意包含 **resources 下的配置/fixture 文件**——
        // 历史上真实泄漏的那一处就藏在 YAML fixture 的 snapshot_id 里，只扫 .java 会漏掉它。
        //
        // 两处刻意的写法，避免**守卫文件自己成为命中**而让守卫失效：
        //   · forbidden 里的词是**拼接**出来的，不写成完整字面量；
        //     新增一个要禁的名字时，照着加一条拼接即可，不要直接写完整词。
        //   · 里程碑标识**一律禁止**「里程碑字母 + 数字 + 连字符」这个裸形式，
        //     而不是试图用词边界把它和正常命名区分开——试过一版带边界的正则，
        //     结果漏掉了真实泄漏（它的前面正好是连字符），是「看起来更精确」的假阳性防护。
        //     真要放行某个含该形式的词，就显式加进 allowedMilestoneLikeTokens。
        //     ⚠️ 注释里也**不要**写具体样例，样例本身就是一个命中。
        List<String> forbidden = List.of(
                "sin" + "an",
                "ops" + "deck",
                "readyFor" + "M"
        );
        Pattern milestoneSnapshotId = Pattern.compile("m[0-9]+-");
        List<String> allowedMilestoneLikeTokens = List.of();

        assertThat(shippedSources).isNotEmpty();
        assertThat(shippedResources).isNotEmpty();
        for (String token : forbidden) {
            assertThat(shippedSources)
                    .as("生产代码不得含内部标识：%s", token)
                    .doesNotContain(token);
            assertThat(shippedResources)
                    .as("生产配置不得含内部标识：%s", token)
                    .doesNotContain(token);
        }
        assertNoMilestoneNumbering("生产代码", shippedSources, milestoneSnapshotId, allowedMilestoneLikeTokens);
        assertNoMilestoneNumbering("生产配置", shippedResources, milestoneSnapshotId, allowedMilestoneLikeTokens);

        assertThat(testFiles).isNotEmpty();
        assertThat(testResourceFiles).isNotEmpty();
        for (Path file : concat(testFiles, testResourceFiles)) {
            String where = ROOT.relativize(file).toString();
            String text = Files.readString(file);
            for (String token : forbidden) {
                assertThat(text)
                        .as("%s 不得含内部标识：%s", where, token)
                        .doesNotContain(token);
            }
            assertNoMilestoneNumbering(where, text, milestoneSnapshotId, allowedMilestoneLikeTokens);
        }
    }

    /**
     * 里程碑式编号（里程碑字母 + 数字 + 连字符）在公开仓里一律禁止。
     * 判据先用 {@link #allowedMilestoneLikeTokens} 做白名单剔除，再匹配模式——
     * 白名单是显式的：要放行什么，就得在这里写下什么。
     */
    private static void assertNoMilestoneNumbering(String where,
                                                  String text,
                                                  Pattern pattern,
                                                  List<String> allowed) {
        String candidate = text;
        for (String token : allowed) {
            candidate = candidate.replace(token, "");
        }
        assertThat(candidate)
                .as("%s 不得含里程碑式编号", where)
                .doesNotContainPattern(pattern);
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

    /** 某个资源根目录下的可读文本文件；目录不存在时返回空表（不是所有模块都有 resources）。 */
    private static List<Path> textFilesUnder(String relativeDir) throws IOException {
        Path root = ROOT.resolve(relativeDir);
        if (!Files.exists(root)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(root)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(MarsCloudGroundingTest::isTextFile)
                    .sorted()
                    .toList();
        }
    }

    private static boolean isTextFile(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        return switch (name.substring(dot + 1).toLowerCase()) {
            case "yml", "yaml", "properties", "json", "xml", "conf", "txt", "sql", "md", "factories", "imports" -> true;
            default -> false;
        };
    }

    private static List<Path> concat(List<Path> first, List<Path> second) {
        List<Path> all = new java.util.ArrayList<>(first);
        all.addAll(second);
        return all;
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
