package com.mars.cloud.service.auth.infrastructure.sms;

import com.mars.cloud.service.auth.configuration.AuthProperties;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;

public final class MockSmsSender implements SmsSender {
    private final Path path;
    public MockSmsSender(AuthProperties properties) {
        path=Path.of(properties.getSms().getMockOutbox()).toAbsolutePath().normalize();
        if (!path.toString().contains("/dev/.local/")) throw new IllegalStateException("Mock outbox must be private local state");
        try {
            Files.createDirectories(path.getParent());
            if (Files.isSymbolicLink(path.getParent()) || Files.exists(path,LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(path))
                throw new IllegalStateException("Mock outbox must not be a symlink");
            Files.setPosixFilePermissions(path.getParent(),PosixFilePermissions.fromString("rwx------"));
            if (!Files.exists(path)) Files.createFile(path);
            Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------"));
        } catch (IOException ex) { throw new IllegalStateException("Cannot prepare mock outbox",ex); }
    }
    @Override public boolean available() { return true; }
    @Override public void send(String phone,String challengeId,String code) {
        byte[] bytes=(challengeId+" "+phone+" "+code+"\n").getBytes(StandardCharsets.US_ASCII);
        try (FileChannel channel=FileChannel.open(path,StandardOpenOption.WRITE,StandardOpenOption.APPEND);
                var lock=channel.lock()) {
            channel.write(ByteBuffer.wrap(bytes));
            channel.force(true);
        } catch (IOException ex) { throw SmsFailure.unavailable(); }
    }
}
