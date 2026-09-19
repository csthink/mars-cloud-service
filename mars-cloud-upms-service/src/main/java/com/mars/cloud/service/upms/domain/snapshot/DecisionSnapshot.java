package com.mars.cloud.service.upms.domain.snapshot;

import com.mars.cloud.service.upms.domain.policy.PlatformRoleKey;
import com.mars.cloud.service.upms.domain.policy.RoleDefinition;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class DecisionSnapshot {

    private final String snapshotId;
    private final Map<String, PlatformRegistry> registries;
    private final Map<PlatformRoleKey, RoleDefinition> roles;
    private final Map<String, Set<PlatformRoleKey>> bindings;

    public DecisionSnapshot(String snapshotId,
                            Collection<PlatformRegistry> registries,
                            Collection<RoleDefinition> roles,
                            Map<String, Set<PlatformRoleKey>> bindings) {
        this.snapshotId = snapshotId;
        this.registries = indexRegistries(registries);
        this.roles = indexRoles(roles);
        this.bindings = copyBindings(bindings);
    }

    public String snapshotId() {
        return snapshotId;
    }

    public Map<String, PlatformRegistry> registries() {
        return registries;
    }

    public Map<PlatformRoleKey, RoleDefinition> roles() {
        return roles;
    }

    public Map<String, Set<PlatformRoleKey>> bindings() {
        return bindings;
    }

    public Optional<PlatformRegistry> registry(String platform) {
        return Optional.ofNullable(registries.get(platform));
    }

    public Optional<RoleDefinition> role(PlatformRoleKey key) {
        return Optional.ofNullable(roles.get(key));
    }

    public Set<PlatformRoleKey> rolesForSubject(String subject) {
        return bindings.getOrDefault(subject, Set.of());
    }

    private static Map<String, PlatformRegistry> indexRegistries(Collection<PlatformRegistry> registries) {
        Map<String, PlatformRegistry> indexed = new LinkedHashMap<>();
        for (PlatformRegistry registry : registries) {
            if (indexed.put(registry.platform(), registry) != null) {
                throw new IllegalArgumentException("duplicate platform registry: " + registry.platform());
            }
        }
        return Map.copyOf(indexed);
    }

    private static Map<PlatformRoleKey, RoleDefinition> indexRoles(Collection<RoleDefinition> roles) {
        Map<PlatformRoleKey, RoleDefinition> indexed = new LinkedHashMap<>();
        for (RoleDefinition role : roles) {
            if (indexed.put(role.key(), role) != null) {
                throw new IllegalArgumentException("duplicate role: " + role.key().asString());
            }
        }
        return Map.copyOf(indexed);
    }

    private static Map<String, Set<PlatformRoleKey>> copyBindings(Map<String, Set<PlatformRoleKey>> bindings) {
        Map<String, Set<PlatformRoleKey>> copied = new LinkedHashMap<>();
        for (Map.Entry<String, Set<PlatformRoleKey>> entry : bindings.entrySet()) {
            copied.put(entry.getKey(), Set.copyOf(new LinkedHashSet<>(entry.getValue())));
        }
        return Map.copyOf(copied);
    }
}
