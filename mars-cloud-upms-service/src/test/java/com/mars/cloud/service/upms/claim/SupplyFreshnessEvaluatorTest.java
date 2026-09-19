package com.mars.cloud.service.upms.claim;

import com.mars.cloud.service.upms.application.service.SupplyFreshnessEvaluator;
import com.mars.cloud.service.upms.domain.snapshot.SupplyFreshness;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SupplyFreshnessEvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-07-05T12:00:00Z");
    private final SupplyFreshnessEvaluator evaluator = new SupplyFreshnessEvaluator();

    @Test
    void sourceSnapshotWithinWindowIsFreshOnlyWhenItMatchesActiveSnapshot() {
        SupplyFreshness fresh = evaluator.evaluate(
                "snapshot-a",
                "snapshot-a",
                1,
                NOW.minus(Duration.ofSeconds(30)),
                NOW,
                Duration.ofMinutes(1)
        );
        SupplyFreshness staleBySource = evaluator.evaluate(
                "snapshot-a",
                "snapshot-b",
                1,
                NOW.minus(Duration.ofSeconds(30)),
                NOW,
                Duration.ofMinutes(1)
        );
        SupplyFreshness staleByAge = evaluator.evaluate(
                "snapshot-a",
                "snapshot-a",
                1,
                NOW.minus(Duration.ofMinutes(2)),
                NOW,
                Duration.ofMinutes(1)
        );

        assertThat(fresh.state()).isEqualTo(SupplyFreshness.State.FRESH);
        assertThat(staleBySource.state()).isEqualTo(SupplyFreshness.State.STALE);
        assertThat(staleByAge.state()).isEqualTo(SupplyFreshness.State.STALE);
        assertThat(staleBySource.sourceSnapshotId()).isEqualTo("snapshot-a");
        assertThat(staleBySource.activeSnapshotId()).isEqualTo("snapshot-b");
    }
}
