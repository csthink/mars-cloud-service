package com.mars.cloud.service.upms;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

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
                .contains("name: mars-cloud-upms-service")
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
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(testConfiguration)
                .contains("spring.profiles.active=local,test")
                .contains("spring.cloud.nacos.config.enabled=false")
                .contains("spring.cloud.nacos.config.import-check.enabled=false")
                .contains("spring.cloud.nacos.discovery.enabled=false");
    }
}
