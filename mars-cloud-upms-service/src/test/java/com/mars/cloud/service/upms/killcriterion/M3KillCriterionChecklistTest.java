package com.mars.cloud.service.upms.killcriterion;

import com.mars.cloud.service.upms.application.dto.M3KillCriterionChecklist;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class M3KillCriterionChecklistTest {

    @Test
    void missingOpsdeckSecondVersionEvidenceIsReportedWithoutClaimingLiveReadiness() {
        M3KillCriterionChecklist checklist = M3KillCriterionChecklist.builder()
                .ingressReachable(false)
                .unifiedSsoLoginVerified(false)
                .k8sAuditSourceConnected(false)
                .claimFixtureConsumedByAgreedReader(false)
                .ownerDecisionRecorded(false)
                .build();

        assertThat(checklist.readyForM5()).isFalse();
        assertThat(checklist.missingItems()).containsExactly(
                "Ingress reachable",
                "Unified SSO login path verified",
                "K8s audit source connected",
                "Claim fixture consumed by agreed reader",
                "Owner decision recorded"
        );
    }

    @Test
    void checklistOnlyPassesWhenAllFiveEvidenceItemsArePresent() {
        M3KillCriterionChecklist checklist = M3KillCriterionChecklist.builder()
                .ingressReachable(true)
                .unifiedSsoLoginVerified(true)
                .k8sAuditSourceConnected(true)
                .claimFixtureConsumedByAgreedReader(true)
                .ownerDecisionRecorded(true)
                .build();

        assertThat(checklist.readyForM5()).isTrue();
        assertThat(checklist.missingItems()).isEmpty();
    }
}
