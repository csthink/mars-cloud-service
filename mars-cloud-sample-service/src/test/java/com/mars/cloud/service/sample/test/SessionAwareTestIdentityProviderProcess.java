package com.mars.cloud.service.sample.test;

import com.mars.cloud.security.test.TestIdentityProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

/** Issues service acceptance tokens with the session claim required by Gateway. */
public final class SessionAwareTestIdentityProviderProcess {
    private SessionAwareTestIdentityProviderProcess() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected an existing private output directory");
        Path directory = Path.of(args[0]);
        if (!Files.isDirectory(directory)) throw new IllegalArgumentException("Output directory must exist");
        var issuer = new TestIdentityProvider();
        Runtime.getRuntime().addShutdownHook(new Thread(issuer::close));
        String[] audiences = {"mars-cloud-sample-service", "mars-cloud-upms-service", "mars-cloud-gateway"};

        var allow = claims(issuer, "caller-allow", audiences);
        allow.put("client_id", "mars-cloud-sample-service");
        write(directory.resolve("allow.token"), issuer.sign(allow));
        write(directory.resolve("admin.token"), issuer.sign(claims(issuer, "local-admin", audiences)));
        var expired = claims(issuer, "local-admin", audiences);
        expired.put("exp", Date.from(Instant.now().minusSeconds(120)));
        write(directory.resolve("expired.token"), issuer.sign(expired));
        write(directory.resolve("deny.token"), issuer.sign(claims(issuer, "caller-deny", audiences)));
        write(directory.resolve("sample-only.token"), issuer.sign(
                claims(issuer, "caller-allow", "mars-cloud-sample-service")));
        write(directory.resolve("missing-sid.token"), issuer.token("caller-allow", audiences));
        var invalidSid = claims(issuer, "caller-allow", audiences);
        invalidSid.put("sid", "invalid:sid");
        write(directory.resolve("invalid-sid.token"), issuer.sign(invalidSid));
        write(directory.resolve("issuer.properties"), "issuer=" + issuer.issuer() + "\njwks=" + issuer.jwksUri() + "\n");
        write(directory.resolve("ready"), "ready\n");
        new CountDownLatch(1).await();
    }

    private static Map<String, Object> claims(TestIdentityProvider issuer, String subject, String... audiences) {
        Map<String, Object> claims = issuer.claims(subject, audiences);
        claims.put("sid", UUID.randomUUID().toString());
        return claims;
    }

    private static void write(Path path, String value) throws Exception {
        Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(path, value);
    }
}
