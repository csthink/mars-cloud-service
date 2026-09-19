package com.mars.cloud.service.upms.domain.policy;

public record CapabilityItem(String id, boolean sensitive, boolean deprecated) {

    public static CapabilityItem normal(String id) {
        return new CapabilityItem(id, false, false);
    }

    public static CapabilityItem sensitive(String id) {
        return new CapabilityItem(id, true, false);
    }

    public static CapabilityItem deprecated(String id) {
        return new CapabilityItem(id, false, true);
    }

    public static CapabilityItem sensitiveDeprecated(String id) {
        return new CapabilityItem(id, true, true);
    }
}
