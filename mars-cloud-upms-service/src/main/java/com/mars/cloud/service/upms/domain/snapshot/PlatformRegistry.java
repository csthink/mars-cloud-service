package com.mars.cloud.service.upms.domain.snapshot;

import com.mars.cloud.service.upms.domain.policy.CapabilityItem;

import java.util.Map;
import java.util.Set;

public record PlatformRegistry(String platform,
                               Set<String> actions,
                               Set<String> resources,
                               int capsVer,
                               String contentHash,
                               Map<String, CapabilityItem> capabilityItems) {

    public PlatformRegistry(String platform, Set<String> actions, Set<String> resources) {
        this(platform, actions, resources, 0, "", Map.of());
    }

    public PlatformRegistry {
        actions = Set.copyOf(actions);
        resources = Set.copyOf(resources);
        capabilityItems = Map.copyOf(capabilityItems);
    }

    public boolean hasAction(String action) {
        return actions.contains(action);
    }

    public boolean hasResource(String resource) {
        return resources.contains(resource);
    }
}
