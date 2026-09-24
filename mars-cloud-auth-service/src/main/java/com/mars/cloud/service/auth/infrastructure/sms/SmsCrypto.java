package com.mars.cloud.service.auth.infrastructure.sms;

import com.mars.cloud.service.auth.configuration.AuthProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public final class SmsCrypto {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final byte[] key;
    public SmsCrypto(AuthProperties properties) {
        String encoded = properties.getSms().getHmacKey();
        key = encoded == null || encoded.isBlank() ? null : Base64.getDecoder().decode(encoded);
    }
    public boolean enabled() { return key != null; }
    public String digest(String purpose, String value) {
        if (key == null) throw new IllegalStateException("SMS key unavailable");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] data = mac.doFinal((purpose + ":" + value).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
        } catch (GeneralSecurityException ex) { throw new IllegalStateException("HMAC unavailable", ex); }
    }
    public static String randomId() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    public static String code() { return "%06d".formatted(RANDOM.nextInt(1_000_000)); }
    public static int random(int bound) { return RANDOM.nextInt(bound); }
}
