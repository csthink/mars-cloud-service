package com.mars.cloud.service.upms.security;

import com.mars.cloud.security.test.TestIdentityProvider;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** A test-only issuer shared for the lifetime of the cached Spring test contexts. */
public abstract class SecurityTestSupport {
    protected static final TestIdentityProvider ISSUER = new TestIdentityProvider();
    static { Runtime.getRuntime().addShutdownHook(new Thread(ISSUER::close)); }
    @DynamicPropertySource
    static void trustProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", ISSUER::issuer);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", ISSUER::jwksUri);
    }
    public static String token(String subject) {
        return ISSUER.token(subject, "mars-cloud-sample-service", "mars-cloud-upms-service");
    }
}
