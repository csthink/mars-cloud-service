package com.mars.cloud.service.upms.infrastructure.config;

import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 向健康运维入口暴露当前生效的非敏感 Nacos 配置版本。
 *
 * <p>每次请求都从 {@link Environment} 读取，使 Nacos 动态刷新结果无需重启即可观察。
 * 这里只暴露版本标记，不暴露配置内容、地址、Namespace 或凭据。
 */
@Component
public class NacosConfigInfoContributor implements InfoContributor {

    static final String CONFIG_REVISION_PROPERTY = "mars.upms.nacos.config-revision";

    private final Environment environment;

    public NacosConfigInfoContributor(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void contribute(Info.Builder builder) {
        builder.withDetail("nacos", Map.of(
                "configRevision",
                environment.getProperty(CONFIG_REVISION_PROPERTY, "unavailable")
        ));
    }
}
