package com.mars.cloud.service.upms.domain.snapshot;

public record SupplyFreshness(String sourceSnapshotId,
                              String activeSnapshotId,
                              int capsVer,
                              State state,
                              int sensitiveDropped) {

    public enum State {
        FRESH,
        STALE
    }

    public SupplyFreshness withSensitiveDropped(int dropped) {
        return new SupplyFreshness(sourceSnapshotId, activeSnapshotId, capsVer, state, dropped);
    }
}
