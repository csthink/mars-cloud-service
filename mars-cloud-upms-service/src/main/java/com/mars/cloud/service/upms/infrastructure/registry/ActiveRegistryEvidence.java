package com.mars.cloud.service.upms.infrastructure.registry;

import java.time.Instant;
import java.util.Set;

public record ActiveRegistryEvidence(String snapshotId,
                                     boolean loaded,
                                     Instant activatedAt,
                                     String source,
                                     String sourceHash,
                                     Set<String> deprecatedCapabilities) {

    public ActiveRegistryEvidence {
        deprecatedCapabilities = Set.copyOf(deprecatedCapabilities);
    }

    public static ActiveRegistryEvidence loaded(String snapshotId,
                                                Instant activatedAt,
                                                String source,
                                                String sourceHash,
                                                Set<String> deprecatedCapabilities) {
        return new ActiveRegistryEvidence(snapshotId, true, activatedAt, source, sourceHash, deprecatedCapabilities);
    }

    public static ActiveRegistryEvidence acceptedOnly(String snapshotId, Set<String> deprecatedCapabilities) {
        return new ActiveRegistryEvidence(snapshotId, false, null, "", "", deprecatedCapabilities);
    }
}
