package com.mars.cloud.service.upms.domain.policy;

import java.util.List;

public record RoleDefinition(PlatformRoleKey key, List<Grant> grants) {

    public RoleDefinition {
        grants = List.copyOf(grants);
    }
}
