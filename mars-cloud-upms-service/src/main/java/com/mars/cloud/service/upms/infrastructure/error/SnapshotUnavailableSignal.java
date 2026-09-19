package com.mars.cloud.service.upms.infrastructure.error;

public class SnapshotUnavailableSignal extends RuntimeException {

    public SnapshotUnavailableSignal() {
        super("active snapshot is unavailable");
    }
}
