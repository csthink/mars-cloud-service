package com.mars.cloud.service.upms.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.info.Info;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class NacosConfigInfoContributorTest {

    @Test
    void exposesOnlyTheCurrentConfigRevision() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(NacosConfigInfoContributor.CONFIG_REVISION_PROPERTY, "revision-2");
        Info.Builder builder = new Info.Builder();

        new NacosConfigInfoContributor(environment).contribute(builder);

        assertThat(builder.build().getDetails())
                .containsEntry("nacos", java.util.Map.of("configRevision", "revision-2"));
    }

    @Test
    void usesUnavailableWhenNoRevisionWasLoaded() {
        Info.Builder builder = new Info.Builder();

        new NacosConfigInfoContributor(new MockEnvironment()).contribute(builder);

        assertThat(builder.build().getDetails())
                .containsEntry("nacos", java.util.Map.of("configRevision", "unavailable"));
    }
}
