package com.mars.cloud.service.auth.configuration;

import com.mars.cloud.service.auth.application.SigningKeyService;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class SigningKeyTest {
    JdbcTemplate jdbc;
    AuthProperties properties;
    AuthEnvironment environment;
    Clock clock=Clock.fixed(Instant.parse("2026-01-01T12:00:00Z"),ZoneOffset.UTC);
    @BeforeEach void setup() {
        jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1"));
        jdbc.execute("CREATE TABLE sys_jwk(kid VARCHAR(100) PRIMARY KEY,public_jwk VARCHAR(4000),private_ciphertext VARBINARY(8192),nonce VARBINARY(12),encryption_key_id VARCHAR(64),created_at TIMESTAMP,retired_at TIMESTAMP,active_key INT UNIQUE)");
        properties=AuthPolicyTest.properties();var e=new MockEnvironment().withProperty("server.ssl.enabled","true").withProperty("server.port","8301");e.setActiveProfiles("test");
        environment=new AuthEnvironment(properties,e);
    }
    @Test void encryptedKeySurvivesReloadAndRejectsWrongRootKey() {
        var first=new SigningKeyService(jdbc,properties,environment,clock);
        assertThat(new SigningKeyService(jdbc,properties,environment,clock).kid()).isEqualTo(first.kid());
        byte[] ciphertext=jdbc.queryForObject("SELECT private_ciphertext FROM sys_jwk",byte[].class);
        assertThat(new String(ciphertext,java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("PRIVATE KEY","\"d\"");
        byte[] wrongKey=new byte[32];wrongKey[0]=1;
        properties.getJwk().setEncryptionKey(Base64.getEncoder().encodeToString(wrongKey));
        assertThatThrownBy(() -> new SigningKeyService(jdbc,properties,environment,clock)).hasMessage("Cannot decrypt signing-key material");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_jwk",Integer.class)).isEqualTo(1);
    }
    @Test void retiredPublicKeysExpireAtTheExactTwentyFourHourBoundary() throws Exception {
        var service=new SigningKeyService(jdbc,properties,environment,clock);
        var old=new RSAKeyGenerator(2048).keyID("retired").generate();
        jdbc.update("INSERT INTO sys_jwk(kid,public_jwk,retired_at) VALUES(?,?,?)",old.getKeyID(),old.toPublicJWK().toJSONString(),Timestamp.from(clock.instant().minus(Duration.ofHours(24)).plusSeconds(1)));
        assertThat(service.signingKeys().getKeys()).hasSize(2);
        assertThat(service.signingKeys().getKeyByKeyId("retired").isPrivate()).isFalse();
        jdbc.update("UPDATE sys_jwk SET retired_at=? WHERE kid='retired'",Timestamp.from(clock.instant().minus(Duration.ofHours(24))));
        assertThat(service.signingKeys().getKeys()).hasSize(1);
    }
    @Test void resourceDecoderOnlyAcceptsRs256WithTheExpectedIdentityClaims() throws Exception {
        var service=new SigningKeyService(jdbc,properties,environment,clock);
        RSAKey key=(RSAKey)service.signingKeys().getKeys().getFirst();
        var config=new AuthorizationServerConfiguration();
        var decoder=config.authJwtDecoder(config.jwkSource(service),properties,clock);
        var claims=new JWTClaimsSet.Builder().issuer(properties.getIssuer()).subject("123")
                .audience("mars-cloud-auth-service").issueTime(Date.from(clock.instant()))
                .expirationTime(Date.from(clock.instant().plusSeconds(900))).claim("tenant_id","default").claim("client_id","portal").build();
        for (var alg:java.util.List.of(JWSAlgorithm.RS256,JWSAlgorithm.RS384,JWSAlgorithm.RS512)) {
            var jwt=new SignedJWT(new JWSHeader.Builder(alg).keyID(key.getKeyID()).build(),claims);jwt.sign(new RSASSASigner(key));
            if (alg.equals(JWSAlgorithm.RS256)) assertThat(decoder.decode(jwt.serialize()).getSubject()).isEqualTo("123");
            else assertThatThrownBy(() -> decoder.decode(jwt.serialize())).isInstanceOf(org.springframework.security.oauth2.jwt.JwtException.class);
        }
    }
}
