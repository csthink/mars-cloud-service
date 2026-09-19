package com.mars.cloud.service.upms.infrastructure.snapshot;

import com.mars.cloud.service.upms.domain.snapshot.DecisionSnapshot;
import com.mars.cloud.service.upms.infrastructure.error.SnapshotUnavailableSignal;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

@Component
public class InMemorySnapshotProvider implements ActiveSnapshotProvider {

    private final SnapshotValidator validator;
    private final AtomicReference<DecisionSnapshot> active = new AtomicReference<>();
    private volatile boolean activeCorrupted;

    public InMemorySnapshotProvider(SnapshotValidator validator) {
        this.validator = validator;
    }

    @Override
    public DecisionSnapshot currentSnapshot() {
        DecisionSnapshot snapshot = active.get();
        if (snapshot == null || activeCorrupted) {
            throw new SnapshotUnavailableSignal();
        }
        return snapshot;
    }

    public void publishCandidate(DecisionSnapshot candidate) {
        validator.validate(candidate);
        active.set(candidate);
        activeCorrupted = false;
    }

    public void markActiveCorrupted() {
        activeCorrupted = true;
    }
}
