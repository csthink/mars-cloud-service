package com.mars.cloud.service.upms.infrastructure.registry;

import com.mars.cloud.service.upms.domain.snapshot.DecisionSnapshot;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public record RegistryValidationContext(List<ActiveRegistryEvidence> activeEvidence,
                                        Instant now,
                                        Duration dwell,
                                        DecisionSnapshot candidateSnapshot) {

    public RegistryValidationContext {
        activeEvidence = List.copyOf(activeEvidence);
    }

    public static RegistryValidationContext of(List<ActiveRegistryEvidence> activeEvidence,
                                               Instant now,
                                               Duration dwell,
                                               DecisionSnapshot candidateSnapshot) {
        return new RegistryValidationContext(activeEvidence, now, dwell, candidateSnapshot);
    }
}
