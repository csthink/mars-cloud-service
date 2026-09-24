package com.mars.cloud.service.auth.application;

import com.mars.cloud.service.auth.configuration.AuthEnvironment;
import com.mars.cloud.service.auth.configuration.AuthProperties;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@DependsOnDatabaseInitialization
public final class SigningKeyService {
    private final JdbcTemplate jdbc;
    private final AuthProperties properties;
    private final Clock clock;
    private final RSAKey active;
    public SigningKeyService(JdbcTemplate jdbc, AuthProperties properties, AuthEnvironment environment, Clock clock) {
        this.jdbc = jdbc; this.properties = properties; this.clock = clock;
        if (jdbc.queryForObject("SELECT COUNT(*) FROM sys_jwk WHERE active_key=1", Integer.class) == 0) initialize();
        active = jdbc.queryForObject("SELECT kid,private_ciphertext,nonce,encryption_key_id,public_jwk FROM sys_jwk WHERE active_key=1", (rs,row) -> {
            if (!properties.getJwk().getEncryptionKeyId().equals(rs.getString(4)))
                throw new IllegalStateException("Signing-key encryption version is unavailable");
            try {
                byte[] plain = crypt(Cipher.DECRYPT_MODE,rs.getBytes(2),rs.getBytes(3),rs.getString(1),rs.getString(4));
                RSAKey key = RSAKey.parse(new String(plain,StandardCharsets.UTF_8));
                java.util.Arrays.fill(plain,(byte)0);
                if (!key.isPrivate() || key.size()<2048 || !key.getKeyID().equals(rs.getString(1))
                        || !key.toPublicJWK().equals(RSAKey.parse(rs.getString(5))))
                    throw new IllegalStateException("Signing-key material is inconsistent");
                return key;
            } catch (Exception ex) { throw new IllegalStateException("Cannot decrypt signing-key material"); }
        });
    }
    private void initialize() {
        try {
            RSAKey key = new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
            byte[] nonce = new byte[12]; new SecureRandom().nextBytes(nonce);
            byte[] plain=key.toJSONString().getBytes(StandardCharsets.UTF_8);
            byte[] encrypted=crypt(Cipher.ENCRYPT_MODE,plain,nonce,key.getKeyID(),properties.getJwk().getEncryptionKeyId());
            java.util.Arrays.fill(plain,(byte)0);
            jdbc.update("INSERT INTO sys_jwk (kid,public_jwk,private_ciphertext,nonce,encryption_key_id,created_at,active_key) VALUES (?,?,?,?,?,?,1)",
                    key.getKeyID(),key.toPublicJWK().toJSONString(),encrypted,nonce,properties.getJwk().getEncryptionKeyId(),Timestamp.from(clock.instant()));
        } catch (DuplicateKeyException ex) {
            // Another instance committed the only active key. The constructor reads it after this statement.
        } catch (Exception ex) { throw new IllegalStateException("Cannot initialize signing-key material"); }
    }
    private byte[] crypt(int mode,byte[] value,byte[] nonce,String kid,String version) throws Exception {
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode,new SecretKeySpec(Base64.getDecoder().decode(properties.getJwk().getEncryptionKey()),"AES"),new GCMParameterSpec(128,nonce));
        cipher.updateAAD((kid+":"+version).getBytes(StandardCharsets.UTF_8));
        return cipher.doFinal(value);
    }
    public String kid() { return active.getKeyID(); }
    public JWKSet signingKeys() {
        List<JWK> result = new ArrayList<>(); result.add(active);
        Instant cutoff=clock.instant().minus(Duration.ofHours(24));
        result.addAll(jdbc.query("SELECT public_jwk FROM sys_jwk WHERE retired_at>?", (rs,row) -> {
            try { return JWK.parse(rs.getString(1)).toPublicJWK(); }
            catch (Exception ex) { throw new IllegalStateException("Invalid retired public key"); }
        },Timestamp.from(cutoff)));
        return new JWKSet(result);
    }
}
