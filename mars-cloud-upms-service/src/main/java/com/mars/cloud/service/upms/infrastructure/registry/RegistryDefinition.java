package com.mars.cloud.service.upms.infrastructure.registry;

import com.mars.cloud.service.upms.domain.policy.CapabilityItem;
import com.mars.cloud.service.upms.domain.snapshot.PlatformRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public record RegistryDefinition(String platform,
                                 int capsVer,
                                 String contentHash,
                                 String syntax,
                                 List<String> actions,
                                 List<CapabilityItem> resources) {

    public RegistryDefinition {
        actions = List.copyOf(actions);
        resources = List.copyOf(resources);
    }

    public PlatformRegistry toPlatformRegistry() {
        Map<String, CapabilityItem> items = resources.stream()
                .collect(Collectors.toMap(
                        CapabilityItem::id,
                        item -> item,
                        (left, right) -> right,
                        LinkedHashMap::new
                ));
        return new PlatformRegistry(
                platform,
                Set.copyOf(actions),
                Set.copyOf(items.keySet()),
                capsVer,
                contentHash,
                items
        );
    }

    public Optional<CapabilityItem> item(String id) {
        return resources.stream()
                .filter(item -> item.id().equals(id))
                .findFirst();
    }

    public RegistryDefinition withActions(List<String> nextActions) {
        return new RegistryDefinition(platform, capsVer, contentHash, syntax, nextActions, resources);
    }

    public RegistryDefinition withCapsVer(int nextCapsVer) {
        return new RegistryDefinition(platform, nextCapsVer, contentHash, syntax, actions, resources);
    }

    public RegistryDefinition withContentHash(String nextContentHash) {
        return new RegistryDefinition(platform, capsVer, nextContentHash, syntax, actions, resources);
    }

    public RegistryDefinition withResources(List<CapabilityItem> nextResources) {
        return new RegistryDefinition(platform, capsVer, contentHash, syntax, actions, nextResources);
    }

    public List<CapabilityItem> resourcesWith(CapabilityItem replacement) {
        List<CapabilityItem> next = new ArrayList<>();
        boolean replaced = false;
        for (CapabilityItem item : resources) {
            if (item.id().equals(replacement.id())) {
                next.add(replacement);
                replaced = true;
            } else {
                next.add(item);
            }
        }
        if (!replaced) {
            next.add(replacement);
        }
        return List.copyOf(next);
    }

    public RegistryDefinition withoutResource(String resource) {
        return new RegistryDefinition(
                platform,
                capsVer,
                contentHash,
                syntax,
                actions,
                resources.stream()
                        .filter(item -> !item.id().equals(resource))
                        .toList()
        );
    }
}
