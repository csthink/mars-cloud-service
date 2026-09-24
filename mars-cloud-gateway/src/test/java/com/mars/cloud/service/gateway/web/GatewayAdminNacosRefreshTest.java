package com.mars.cloud.service.gateway.web;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.nacos.api.config.ConfigService;
import com.mars.cloud.security.test.TestIdentityProvider;
import com.mars.cloud.service.gateway.GatewayApplication;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in check that a real Nacos update reaches the live admin policy without restarting Gateway. */
@EnabledIfEnvironmentVariable(named = "MARS_REAL_NACOS_TEST", matches = "true")
@SpringBootTest(classes = GatewayApplication.class, properties = {
        "spring.cloud.nacos.config.enabled=true",
        "spring.cloud.nacos.discovery.enabled=false",
        "management.health.redis.enabled=false",
        "mars.observability.logging.console-format=plain"
})
@ActiveProfiles("local")
class GatewayAdminNacosRefreshTest {
    private static final TestIdentityProvider ISSUER = new TestIdentityProvider();
    private static final String DATA_ID = "mars-cloud-gateway.yaml";
    private static final String GROUP = "DEFAULT_GROUP";

    @Autowired NacosConfigManager nacos;
    @Autowired GatewayAdminIpAllowlist allowlist;
    @MockitoBean ReactiveStringRedisTemplate redis;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", ISSUER::issuer);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", ISSUER::jwksUri);
    }

    @AfterAll
    static void closeIssuer() {
        ISSUER.close();
    }

    @Test
    void nacosRefreshReplacesValidPolicyAndRetainsItAfterInvalidUpdate() throws Exception {
        ConfigService config = nacos.getConfigService();
        String original = config.getConfig(DATA_ID, GROUP, 5_000);
        assertNotNull(original);
        assertFalse(original.isBlank());
        assertFalse(original.contains(GatewayAdminIpAllowlist.PROPERTY), "Use an isolated Nacos application config");
        String previousEnabled = System.getProperty("spring.cloud.nacos.config.enabled");
        System.setProperty("spring.cloud.nacos.config.enabled", "true");
        try {
            assertTrue(config.publishConfig(DATA_ID, GROUP, original + "\n"
                    + GatewayAdminIpAllowlist.PROPERTY + ": '127.0.0.1/32'\n"));
            await(() -> allowlist.allows("127.0.0.1"));

            assertTrue(config.publishConfig(DATA_ID, GROUP, original + "\n"
                    + GatewayAdminIpAllowlist.PROPERTY + ": '127.0.0.1/32,invalid-host'\n"));
            Thread.sleep(2_000);
            assertTrue(allowlist.allows("127.0.0.1"));

            assertTrue(config.publishConfig(DATA_ID, GROUP, original + "\n"
                    + GatewayAdminIpAllowlist.PROPERTY + ": ''\n"));
            await(() -> !allowlist.allows("127.0.0.1"));
        } finally {
            try {
                assertTrue(config.publishConfig(DATA_ID, GROUP, original));
                awaitConfig(config, original);
            } finally {
                if (previousEnabled == null) System.clearProperty("spring.cloud.nacos.config.enabled");
                else System.setProperty("spring.cloud.nacos.config.enabled", previousEnabled);
            }
        }
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(200);
        assertTrue(condition.getAsBoolean(), "Nacos update did not refresh the admin IP policy");
    }

    private static void awaitConfig(ConfigService config, String expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            if (expected.equals(config.getConfig(DATA_ID, GROUP, 5_000))) return;
            Thread.sleep(200);
        }
        fail("Nacos application config was not restored");
    }
}
