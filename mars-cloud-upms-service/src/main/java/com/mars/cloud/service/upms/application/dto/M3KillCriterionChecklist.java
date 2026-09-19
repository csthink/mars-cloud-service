package com.mars.cloud.service.upms.application.dto;

import java.util.ArrayList;
import java.util.List;

public record M3KillCriterionChecklist(boolean ingressReachable,
                                       boolean unifiedSsoLoginVerified,
                                       boolean k8sAuditSourceConnected,
                                       boolean claimFixtureConsumedByAgreedReader,
                                       boolean ownerDecisionRecorded) {

    public static Builder builder() {
        return new Builder();
    }

    public boolean readyForM5() {
        return missingItems().isEmpty();
    }

    public List<String> missingItems() {
        List<String> missing = new ArrayList<>();
        if (!ingressReachable) {
            missing.add("Ingress reachable");
        }
        if (!unifiedSsoLoginVerified) {
            missing.add("Unified SSO login path verified");
        }
        if (!k8sAuditSourceConnected) {
            missing.add("K8s audit source connected");
        }
        if (!claimFixtureConsumedByAgreedReader) {
            missing.add("Claim fixture consumed by agreed reader");
        }
        if (!ownerDecisionRecorded) {
            missing.add("Owner decision recorded");
        }
        return List.copyOf(missing);
    }

    public static final class Builder {
        private boolean ingressReachable;
        private boolean unifiedSsoLoginVerified;
        private boolean k8sAuditSourceConnected;
        private boolean claimFixtureConsumedByAgreedReader;
        private boolean ownerDecisionRecorded;

        public Builder ingressReachable(boolean value) {
            this.ingressReachable = value;
            return this;
        }

        public Builder unifiedSsoLoginVerified(boolean value) {
            this.unifiedSsoLoginVerified = value;
            return this;
        }

        public Builder k8sAuditSourceConnected(boolean value) {
            this.k8sAuditSourceConnected = value;
            return this;
        }

        public Builder claimFixtureConsumedByAgreedReader(boolean value) {
            this.claimFixtureConsumedByAgreedReader = value;
            return this;
        }

        public Builder ownerDecisionRecorded(boolean value) {
            this.ownerDecisionRecorded = value;
            return this;
        }

        public M3KillCriterionChecklist build() {
            return new M3KillCriterionChecklist(
                    ingressReachable,
                    unifiedSsoLoginVerified,
                    k8sAuditSourceConnected,
                    claimFixtureConsumedByAgreedReader,
                    ownerDecisionRecorded
            );
        }
    }
}
