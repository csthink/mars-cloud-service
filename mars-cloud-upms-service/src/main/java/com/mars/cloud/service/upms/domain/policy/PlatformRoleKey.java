package com.mars.cloud.service.upms.domain.policy;

public record PlatformRoleKey(String platform, String roleName) {

    public PlatformRoleKey {
        if (platform == null || platform.isEmpty()) {
            throw new IllegalArgumentException("role platform must be non-empty");
        }
        if (roleName == null || roleName.isEmpty()) {
            throw new IllegalArgumentException("role name must be non-empty");
        }
    }

    public static PlatformRoleKey parse(String value) {
        int separator = value.indexOf('/');
        if (separator <= 0 || separator == value.length() - 1) {
            throw new IllegalArgumentException("role key must be <platform>/<role_name>");
        }
        return new PlatformRoleKey(value.substring(0, separator), value.substring(separator + 1));
    }

    public String asString() {
        return platform + "/" + roleName;
    }
}
