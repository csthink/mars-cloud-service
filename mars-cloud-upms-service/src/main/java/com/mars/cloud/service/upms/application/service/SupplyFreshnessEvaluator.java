package com.mars.cloud.service.upms.application.service;

import com.mars.cloud.service.upms.domain.snapshot.SupplyFreshness;

import java.time.Duration;
import java.time.Instant;

public class SupplyFreshnessEvaluator {

    public SupplyFreshness evaluate(String sourceSnapshotId,
                                    String activeSnapshotId,
                                    int capsVer,
                                    Instant lastSyncAt,
                                    Instant now,
                                    Duration freshnessWindow) {
        boolean sameSnapshot = sourceSnapshotId.equals(activeSnapshotId);
        boolean withinWindow = Duration.between(lastSyncAt, now).compareTo(freshnessWindow) <= 0;
        SupplyFreshness.State state = sameSnapshot && withinWindow
                ? SupplyFreshness.State.FRESH
                : SupplyFreshness.State.STALE;
        return new SupplyFreshness(sourceSnapshotId, activeSnapshotId, capsVer, state, 0);
    }
}
