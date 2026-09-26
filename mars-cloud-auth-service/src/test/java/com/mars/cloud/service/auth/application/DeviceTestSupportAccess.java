package com.mars.cloud.service.auth.application;

import java.util.List;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

/** Public facade over the package-private test support for tests in other packages. */
public final class DeviceTestSupportAccess {
    public static final long USER = DeviceTestSupport.USER;
    public static final java.time.Instant START = DeviceTestSupport.START;
    private DeviceTestSupportAccess() { }
    public static final class TestClock extends DeviceTestSupport.TestClock { public TestClock() { } @Override public void set(java.time.Instant instant) { super.set(instant); } }
    public static DriverManagerDataSource dataSource() { return DeviceTestSupport.dataSource(); }
    public static RegisteredClient nativeClient() { return DeviceTestSupport.nativeClient(); }
    public static UsernamePasswordAuthenticationToken resourceOwner() { return DeviceTestSupport.resourceOwner(); }
    @SuppressWarnings("unused") private static final List<String> KEEP = List.of();
}
