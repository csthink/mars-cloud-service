package com.mars.cloud.service.upms.sinan;

import com.mars.cloud.service.upms.domain.policy.Grant;
import com.mars.cloud.service.upms.domain.policy.PlatformRoleKey;
import com.mars.cloud.service.upms.domain.policy.RoleDefinition;
import com.mars.cloud.service.upms.domain.snapshot.DecisionSnapshot;
import com.mars.cloud.service.upms.domain.snapshot.PlatformRegistry;

import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SinanDecisionReplayFixtures {

    private SinanDecisionReplayFixtures() {
    }

    public static DecisionSnapshot activeSnapshot(String snapshotId) {
        return new DecisionSnapshot(
                snapshotId,
                List.of(new PlatformRegistry(
                        "sinan",
                        Set.of("write", "comment"),
                        Set.of("namespace/workload", "namespace/other", "gitlab/issue-7")
                )),
                List.of(
                        role("sinan", "Writer", grant("sinan", "write", "namespace/workload")),
                        role("sinan", "IssueCommenter", grant("sinan", "comment", "gitlab/issue-7"))
                ),
                Map.of(
                        "sinan-writer", Set.of(new PlatformRoleKey("sinan", "Writer")),
                        "sinan-commenter", Set.of(new PlatformRoleKey("sinan", "IssueCommenter")),
                        "sinan-adminish", Set.of(
                                new PlatformRoleKey("sinan", "Writer"),
                                new PlatformRoleKey("sinan", "IssueCommenter")
                        )
                )
        );
    }

    public static List<SinanDecisionReplayCase> replayCases() {
        return List.of(
                new SinanDecisionReplayCase(
                        "sinan-writer",
                        "write",
                        "sinan:namespace/workload",
                        "allow",
                        "writer workload write"
                ),
                new SinanDecisionReplayCase(
                        "sinan-commenter",
                        "write",
                        "sinan:namespace/workload",
                        "deny",
                        "commenter cannot write workload"
                ),
                new SinanDecisionReplayCase(
                        "sinan-writer",
                        "write",
                        "sinan:namespace/other",
                        "deny",
                        "registered resource without grant"
                ),
                new SinanDecisionReplayCase(
                        "sinan-commenter",
                        "comment",
                        "sinan:gitlab/issue-7",
                        "allow",
                        "labeled issue comment sample"
                ),
                new SinanDecisionReplayCase(
                        "sinan-outsider",
                        "comment",
                        "sinan:gitlab/issue-7",
                        "deny",
                        "unbound caller deny"
                ),
                new SinanDecisionReplayCase(
                        "sinan-adminish",
                        "comment",
                        "sinan:gitlab/issue-7",
                        "allow",
                        "multi-role union allow"
                )
        );
    }

    public static DecisionSnapshot noGrantSnapshot(String snapshotId) {
        return new DecisionSnapshot(
                snapshotId,
                List.of(new PlatformRegistry(
                        "sinan",
                        Set.of("write"),
                        Set.of("namespace/workload")
                )),
                List.of(role("sinan", "Writer", grant("sinan", "write", "namespace/workload"))),
                Map.of()
        );
    }

    private static RoleDefinition role(String platform, String name, Grant grant) {
        return new RoleDefinition(new PlatformRoleKey(platform, name), List.of(grant));
    }

    private static Grant grant(String platform, String action, String resource) {
        return new Grant(platform, action, resource);
    }
}
