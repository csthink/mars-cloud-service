package com.mars.cloud.service.upms.infrastructure.snapshot;

import com.mars.cloud.service.upms.domain.snapshot.DecisionSnapshot;

public interface ActiveSnapshotProvider {

    DecisionSnapshot currentSnapshot();
}
